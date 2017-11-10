package com.box.l10n.mojito.okapi.filters;

import static com.box.l10n.mojito.okapi.filters.POFilter.logger;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.skeleton.GenericSkeleton;

/**
 * Help with processing and completing plural form when working with Okapi
 * filters.
 *
 * @author jeanaurambault
 */
public abstract class PluralsHolder {

    Event zero = null;
    Event one = null;
    Event two = null;
    Event few = null;
    Event many = null;
    Event other = null;

    Multimap<String, String> getFormsToCopy(LocaleId localeId) {

        LinkedHashMultimap<String, String> formsToCopy = LinkedHashMultimap.create();

        List<String> pluralForms = new ArrayList<>();
        pluralForms.add("zero");
        pluralForms.add("one");
        pluralForms.add("two");
        pluralForms.add("few");
        pluralForms.add("many");
        pluralForms.add("other");

        retainForms(localeId, pluralForms);

        for (String pluralForm : pluralForms) {
            formsToCopy.put("other", pluralForm);
        }

        return formsToCopy;
    }

    void retainForms(LocaleId localeId, List<String> pluralForms) {
        if (localeId != null && !LocaleId.EMPTY.equals(localeId)) {
            ULocale ulocale = ULocale.forLanguageTag(localeId.toBCP47());
            PluralRules pluralRules = PluralRules.forLocale(ulocale);
            pluralForms.retainAll(pluralRules.getKeywords());
        }
    }

    public List<Event> getCompletedForms(LocaleId localeId) {

        List<Event> newForms = new ArrayList<>();

        String otherName = other.getTextUnit().getName();

        for (Map.Entry<String, String> entry : getFormsToCopy(localeId).entries()) {
            String sourceForm = entry.getKey();
            String targetForm = entry.getValue();

            logger.debug("Complete form {} with {}", targetForm, sourceForm);

            Event sourceEvent = getEventForPluralForm(sourceForm);
            Event targetEvent = getEventForPluralForm(targetForm);
            if (targetEvent == null) {
                targetEvent = createCopyOf(sourceEvent, sourceForm, targetForm);
                setEventForPluralForm(targetEvent, targetForm);
            }
            targetEvent.getTextUnit().setAnnotation(new PluralFormAnnotation(targetForm, otherName));
            newForms.add(targetEvent);

        }

        return newForms;
    }

    protected Event getEventForPluralForm(String pluralForm) {
        Event event = null;
        switch (pluralForm) {
            case "zero":
                event = zero;
                break;
            case "one":
                event = one;
                break;
            case "two":
                event = two;
                break;
            case "few":
                event = few;
                break;
            case "many":
                event = many;
                break;
            case "other":
                event = other;
                break;
            default:
                throw new RuntimeException("Invalid plural form");
        }

        return event;
    }

    protected Event setEventForPluralForm(Event event, String pluralForm) {
        switch (pluralForm) {
            case "zero":
                zero = event;
                break;
            case "one":
                one = event;
                break;
            case "two":
                two = event;
                break;
            case "few":
                few = event;
                break;
            case "many":
                many = event;
                break;
            case "other":
                other = event;
                break;
            default:
                throw new RuntimeException("Invalid plural form");
        }

        return event;
    }

    protected void loadEvents(List<Event> pluralEvents) {

        for (Event pluralEvent : pluralEvents) {
            String cldrPluralForm = getCldrPluralFormOfEvent(pluralEvent);
            if (null != cldrPluralForm) {
                adaptTextUnitToCLDRForm(pluralEvent.getTextUnit(), cldrPluralForm);
                setEventForPluralForm(pluralEvent, cldrPluralForm);
            }
        }
    }

    Event createCopyOf(Event event, String sourceForm, String targetForm) {
        logger.debug("Create copy of: {}, source form: {}, target form: {}", event.getTextUnit().getName(), sourceForm, targetForm);
        ITextUnit textUnit = event.getTextUnit().clone();
        renameTextUnit(textUnit, targetForm);
        replaceFormInSkeleton((GenericSkeleton) textUnit.getSkeleton(), sourceForm, targetForm);
        Event copyOfOther = new Event(EventType.TEXT_UNIT, textUnit);
        return copyOfOther;
    }

    void renameTextUnit(ITextUnit textUnit, String targetForm) {
        textUnit.setName(getNewTextUnitName(textUnit.getName(), targetForm));
    }

    boolean hasCopyFormsOnImport() {
        return false;
    }

    abstract String getCldrPluralFormOfEvent(Event pluralEvent);

    abstract String getNewTextUnitName(String name, String targetForm);

    abstract void adaptTextUnitToCLDRForm(ITextUnit textUnit, String cldrPluralForm);

    abstract void replaceFormInSkeleton(GenericSkeleton genericSkeleton, String sourceForm, String targetForm);

}