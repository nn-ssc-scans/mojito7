package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.cli.command.checks.AbstractCliChecker;
import com.box.l10n.mojito.cli.command.checks.CheckerOptionsMapEntry;
import com.box.l10n.mojito.cli.command.checks.CheckerOptionsMapEntryConverter;
import com.box.l10n.mojito.cli.command.checks.CliCheckResult;
import com.box.l10n.mojito.cli.command.checks.CliCheckerExecutor;
import com.box.l10n.mojito.cli.command.checks.CliCheckerOptions;
import com.box.l10n.mojito.cli.command.checks.CliCheckerType;
import com.box.l10n.mojito.cli.command.checks.CliCheckerTypeConverter;
import com.box.l10n.mojito.cli.command.checks.PlaceholderRegularExpressionConverter;
import com.box.l10n.mojito.cli.command.checks.ExtractionCheckThirdPartyNotificationTypeConverter;
import com.box.l10n.mojito.cli.command.extraction.AssetExtractionDiff;
import com.box.l10n.mojito.cli.command.extraction.ExtractionDiffPaths;
import com.box.l10n.mojito.cli.command.extraction.ExtractionDiffService;
import com.box.l10n.mojito.cli.command.extraction.ExtractionPaths;
import com.box.l10n.mojito.cli.command.extraction.MissingExtractionDirectoryExcpetion;
import com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckNotificationSender;
import com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckNotificationSenderException;
import com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckNotificationSenderPhabricator;
import com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckNotificationSenderSlack;
import com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckThirdPartyNotificationService;
import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.regex.PlaceholderRegularExpressions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.box.l10n.mojito.cli.command.extractioncheck.ExtractionCheckNotificationSender.QUOTE_MARKER;

/**
 * Command to execute checks against any new source strings
 */
@Component
@Scope("prototype")
@Parameters(commandNames = {"extraction-check"}, commandDescription = "Execute checks against new source strings")
public class ExtractionCheckCommand extends Command {

    static Logger logger = LoggerFactory.getLogger(ExtractionDiffCommand.class);

    @Autowired
    ExtractionDiffService extractionDiffService;

    @Autowired
    ConsoleWriter consoleWriter;

    @Parameter(names = {"--checker-list", "-cl"}, variableArity = true, required = true, converter = CliCheckerTypeConverter.class, description = "List of checks to be run against new source strings")
    List<CliCheckerType> checkerList;

    @Parameter(names = {"--hard-fail", "-hf"}, variableArity = true, required = false, description = "List of checks that will cause a hard failure, use ALL if all checks should be hard failures")
    List<String> hardFailList = new ArrayList<>();

    @Parameter(names = {"--parameter-regexes", "-pr"}, variableArity = true, required = false, converter = PlaceholderRegularExpressionConverter.class, description = "Regex types used to identify parameters in source strings")
    List<PlaceholderRegularExpressions> parameterRegexList = new ArrayList<>();

    @Parameter(names = {"--name", "-n"}, arity = 1, required = false, description = ExtractionDiffCommand.EXCTRACTION_DIFF_NAME_DESCRIPTION)
    String extractionDiffName = null;

    @Parameter(names = {"--phab-message-template", "-pmt"}, arity = 1, required = false, description = "Optional message template to customize the Phabricator notification message. eg. '{baseMessage}. Check [[https://build.org/1234|build]].' ")
    String phabMessageTemplate = "{baseMessage}";

    @Parameter(names = {"--slack-message-template", "-smt"}, arity = 1, required = false, description = "Optional message template to customize the Slack notification message. eg. '{baseMessage}. Check [[https://build.org/1234|build]].' ")
    String slackMessageTemplate = "{baseMessage}";

    @Parameter(names = {"--hard-fail-message", "-hfm"}, arity = 1, required = false, description = "Optional string appended to notification messages in the event of a hard failure.")
    String hardFailureMessage = null;

    @Parameter(names = {"--current", "-c"}, arity = 1, required = true, description = ExtractionDiffCommand.CURRENT_EXTRACTION_NAME_DESCRIPTION)
    String currentExtractionName;

    @Parameter(names = {"--base", "-b"}, arity = 1, required = true, description = ExtractionDiffCommand.BASE_EXTRACTION_NAME_DESCRIPTION)
    String baseExtractionName;

    @Parameter(names = {"--output-directory", "-o"}, arity = 1, required = false, description = ExtractionDiffCommand.OUTPUT_DIRECTORY_DESCRIPTION)
    String outputDirectoryParam = ExtractionDiffPaths.DEFAULT_OUTPUT_DIRECTORY;

    @Parameter(names = {"--input-directory", "-i"}, arity = 1, required = false, description = ExtractionDiffCommand.INPUT_DIRECTORY_DESCRIPTION)
    String inputDirectoryParam = ExtractionPaths.DEFAULT_OUTPUT_DIRECTORY;

    @Parameter(names = {"--checker-options", "-co"}, variableArity = true, required = false, converter = CheckerOptionsMapEntryConverter.class, description = "Map of options to be used in individual checkers. Options must be in the form 'name':'value'.")
    List<CheckerOptionsMapEntry> checkerOptions = new ArrayList<>();

    @Parameter(names = {"--notify-services", "-ns"}, variableArity = true, required = false, converter = ExtractionCheckThirdPartyNotificationTypeConverter.class, description = "List of services to send notification messages")
    List<ExtractionCheckThirdPartyNotificationService> thirdPartyNotificationTypes = new ArrayList<>();

    @Parameter(names = {"--username", "-u"}, arity = 1, required = false, description = "Mojito username, required if sending Slack notifications.")
    String username;

    @Parameter(names = {"--slack-email-pattern", "-sep"}, arity = 1, required = false, description = "Email pattern used for Slack notifications, required if sending notifications to Slack.")
    String slackEmailPattern = "";

    @Parameter(names = {"--slack-use-direct-message", "-sudm"}, arity = 1, required = false, description = "Use direct message for Slack notifications. Default is false.")
    boolean slackUseDirectMessage = false;

    @Parameter(names = {"--phab-object-id", "-poid"}, arity = 1, required = false, description = "Phabricator object id, required if sending Phabricator notifications.")
    String phabObjectId;

    @Parameter(names = {"--skip-checks", "-sc"}, arity = 1, required = false, description = "Skips all checks if set to true, useful for skipping checks in automated scripts.")
    boolean areChecksSkipped = false;

    @Parameter(names = {"--checks-skipped-message", "-csm"}, arity = 1, required = false, description = "Optional notification message to be sent when checks are skipped.")
    String checksSkippedMessage;

    List<ExtractionCheckNotificationSender> extractionCheckNotificationSenders = new ArrayList<>();

    @Override
    protected void execute() throws CommandException {

        initNotificationSenders();

        if (areChecksSkipped) {
            consoleWriter.fg(Ansi.Color.YELLOW).newLine().a("Checks disabled as --skip-checks is set to true.").println();
            if (!Strings.isNullOrEmpty(checksSkippedMessage)) {
                sendChecksSkippedNotifications();
            }
        } else {
            ExtractionPaths baseExtractionPaths = new ExtractionPaths(inputDirectoryParam, baseExtractionName);
            ExtractionPaths currentExtractionPaths = new ExtractionPaths(inputDirectoryParam, currentExtractionName);
            ExtractionDiffPaths extractionDiffPaths = ExtractionDiffPaths.builder()
                    .outputDirectory(outputDirectoryParam)
                    .diffExtractionName(extractionDiffName)
                    .baseExtractorPaths(baseExtractionPaths)
                    .currentExtractorPaths(currentExtractionPaths)
                    .build();

            List<AssetExtractionDiff> assetExtractionDiffs;

            try {
                if (extractionDiffService.hasAddedTextUnits(extractionDiffPaths)) {
                    assetExtractionDiffs = extractionDiffService.findAssetExtractionDiffsWithAddedTextUnits(extractionDiffPaths);
                    CliCheckerExecutor cliCheckerExecutor = getCliCheckerExecutor();
                    List<CliCheckResult> cliCheckerFailures = executeChecks(cliCheckerExecutor, assetExtractionDiffs);
                    checkForHardFail(cliCheckerFailures);
                    if (!cliCheckerFailures.isEmpty()) {
                        List<CliCheckResult> failures = getCheckerFailures(cliCheckerFailures).collect(Collectors.toList());
                        outputFailuresToCommandLine(failures);
                        sendFailureNotifications(failures, false);
                    }
                } else {
                    consoleWriter.newLine().a("No new strings in diff to be checked.").println();
                }
            } catch (MissingExtractionDirectoryExcpetion missingExtractionDirectoryException) {
                throw new CommandException("Can't compute extraction diffs", missingExtractionDirectoryException);
            }
            consoleWriter.fg(Ansi.Color.GREEN).newLine().a("Checks completed").println(2);
        }
    }

    private void initNotificationSenders() {
        extractionCheckNotificationSenders = thirdPartyNotificationTypes.stream()
                .map(thirdPartyNotificationType -> getExtractionCheckNotificationSender(thirdPartyNotificationType))
                .collect(Collectors.toList());
    }

    private ExtractionCheckNotificationSender getExtractionCheckNotificationSender(ExtractionCheckThirdPartyNotificationService thirdPartyNotificationType) {
        ExtractionCheckNotificationSender extractionCheckNotificationSender;
        switch (thirdPartyNotificationType) {
            case PHABRICATOR:
                extractionCheckNotificationSender = new ExtractionCheckNotificationSenderPhabricator(phabObjectId, phabMessageTemplate, hardFailureMessage, checksSkippedMessage);
                break;
            case SLACK:
                extractionCheckNotificationSender = new ExtractionCheckNotificationSenderSlack(username, slackEmailPattern, slackMessageTemplate, hardFailureMessage, checksSkippedMessage, slackUseDirectMessage);
                break;
            default:
                throw new CommandException("Unsupported notification type " + thirdPartyNotificationType.name());
        }

        return extractionCheckNotificationSender;
    }

    private void sendChecksSkippedNotifications() {
        for (ExtractionCheckNotificationSender notificationSender : extractionCheckNotificationSenders) {
            try {
                notificationSender.sendChecksSkippedNotification();
            } catch (ExtractionCheckNotificationSenderException e) {
                logger.error("Error sending notification: " + e.getMessage());
                consoleWriter.fg(Ansi.Color.RED).newLine().a("Error sending notification: " + e.getMessage()).println();
            }
        }
    }

    private void sendFailureNotifications(List<CliCheckResult> failures, boolean hardFail) {

        consoleWriter.newLine().a("Sending notifications").println();
        extractionCheckNotificationSenders.stream().forEach(notificationSender -> {
            try {
                notificationSender.sendFailureNotification(failures, hardFail);
            } catch (ExtractionCheckNotificationSenderException e) {
                logger.error("Error sending notification: " + e.getMessage());
                consoleWriter.fg(Ansi.Color.RED).newLine().a("Error sending notification: " + e.getMessage()).println();
            }
        });
    }

    private void checkForHardFail(List<CliCheckResult> results) {
        if (getCheckerHardFailures(results).count() > 0) {
            String cliFailureString = "The following checks had hard failures:" + System.lineSeparator() +
                    getCheckerHardFailures(results).map(failure -> failure.getCheckName() + " failures: " + System.lineSeparator() + failure.getNotificationText().replaceAll(QUOTE_MARKER, "'") + System.lineSeparator())
                            .collect(Collectors.joining(System.lineSeparator()));

            logger.debug(cliFailureString);
            sendFailureNotifications(results.stream().filter(result -> !result.isSuccessful()).collect(Collectors.toList()), true);
            throw new CommandException(cliFailureString);
        }
    }

    private List<CliCheckResult> executeChecks(CliCheckerExecutor cliCheckerExecutor, List<AssetExtractionDiff> assetExtractionDiffs) {
        consoleWriter.newLine().a("Running checks against new strings").println();
        return cliCheckerExecutor.executeChecks(assetExtractionDiffs).stream()
                .filter(result -> !result.isSuccessful())
                .collect(Collectors.toList());
    }

    private Stream<CliCheckResult> getCheckerFailures(List<CliCheckResult> results) {
        return results.stream().filter(result -> !result.isSuccessful());
    }

    private Stream<CliCheckResult> getCheckerHardFailures(List<CliCheckResult> results) {
        return getCheckerFailures(results).filter(CliCheckResult::isHardFail);
    }

    private CliCheckerExecutor getCliCheckerExecutor() {
        return new CliCheckerExecutor(generateCheckerList(generateCheckerOptions()));
    }

    private void outputFailuresToCommandLine(List<CliCheckResult> failedCheckNames) {
        consoleWriter.fg(Ansi.Color.YELLOW).newLine().a("Failed checks: ").println();
        failedCheckNames.stream().forEach(check -> {
            consoleWriter.fg(Ansi.Color.YELLOW).newLine().a(check.getCheckName()).println();
            consoleWriter.fg(Ansi.Color.YELLOW).newLine().a(check.getNotificationText().replaceAll("\\*", "\t*")
                    .replaceAll(QUOTE_MARKER, "'")).println();
        });
    }

    private CliCheckerOptions generateCheckerOptions() {
        Set<PlaceholderRegularExpressions> regexSet = Sets.newHashSet(parameterRegexList);
        Set<CliCheckerType> hardFailureSet = getClassNamesOfHardFailures();
        return new CliCheckerOptions(regexSet, hardFailureSet, buildOptionsMap());
    }

    private ImmutableMap<String, String> buildOptionsMap() {
        return ImmutableMap.<String, String>builder().putAll(checkerOptions.stream()
                        .collect(Collectors.toMap(CheckerOptionsMapEntry::getKey, CheckerOptionsMapEntry::getValue)))
                .build();
    }

    private Set<CliCheckerType> getClassNamesOfHardFailures() {
        if (hardFailList.stream().anyMatch(checkName -> "ALL".equalsIgnoreCase(checkName))) {
            return Stream.of(CliCheckerType.values()).collect(Collectors.toSet());
        } else {
            return hardFailList.stream().map(check -> {
                try {
                    return CliCheckerType.valueOf(check);
                } catch (IllegalArgumentException e) {
                    throw new CommandException("Unknown check name in hard fail list '" + check + "'");
                }
            }).collect(Collectors.toSet());
        }
    }

    private List<AbstractCliChecker> generateCheckerList(CliCheckerOptions checkerOptions) {
        List<AbstractCliChecker> checkers = new ArrayList<>();
        for (CliCheckerType checkerType : checkerList) {
            AbstractCliChecker checker = checkerType.getCliChecker();
            checker.setCliCheckerOptions(checkerOptions);
            checkers.add(checker);
        }

        return checkers;
    }

}