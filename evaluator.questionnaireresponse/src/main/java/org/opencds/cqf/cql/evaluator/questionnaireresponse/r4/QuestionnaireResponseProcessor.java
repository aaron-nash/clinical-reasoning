package org.opencds.cqf.cql.evaluator.questionnaireresponse.r4;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.opencds.cqf.cql.evaluator.fhir.Constants;
import org.opencds.cqf.cql.evaluator.fhir.dal.FhirDal;
import org.opencds.cqf.cql.evaluator.questionnaireresponse.BaseQuestionnaireResponseProcessor;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class QuestionnaireResponseProcessor extends BaseQuestionnaireResponseProcessor<QuestionnaireResponse> {

    public QuestionnaireResponseProcessor(FhirContext fhirContext, FhirDal fhirDal) {
        super(fhirContext, fhirDal);
    }

    protected IBaseBundle createResourceBundle(QuestionnaireResponse questionnaireResponse, List<IBaseResource> resources) {
        var newBundle = new Bundle();
        var bundleId = new Identifier();
        bundleId.setValue("QuestionnaireResponse/" + questionnaireResponse.getIdElement().getIdPart());
        newBundle.setType(Bundle.BundleType.TRANSACTION);
        newBundle.setIdentifier(bundleId);
        resources.forEach(resource -> {
            var request = new Bundle.BundleEntryRequestComponent();
            request.setMethod(Bundle.HTTPVerb.PUT);
            request.setUrl(resource.fhirType() + "/" + resource.getIdElement().getIdPart());

            var entry = new Bundle.BundleEntryComponent();
            entry.setResource((Resource) resource);
            entry.setRequest(request);
            newBundle.addEntry(entry);
        });

        return newBundle;
    }

    public List<IBaseResource> processItems(QuestionnaireResponse questionnaireResponse) {
        var questionnaireCanonical = questionnaireResponse.getQuestionnaire();
        if (questionnaireCanonical == null || questionnaireCanonical.isEmpty()) {
            throw new IllegalArgumentException("The QuestionnaireResponse must have the source Questionnaire specified to do extraction");
        }

        var resources = new ArrayList<IBaseResource>();
        var subject = questionnaireResponse.getSubject();
        var itemExtractionContext = questionnaireResponse.getExtensionByUrl(Constants.SDC_QUESTIONNAIRE_ITEM_EXTRACTION_CONTEXT);
        if (itemExtractionContext != null) {
            processDefinitionItem(itemExtractionContext, "root", questionnaireResponse.getItem(), questionnaireResponse, resources, subject);
        } else {
            var questionnaireCodeMap = getQuestionnaireCodeMap(questionnaireCanonical);
            questionnaireResponse.getItem().forEach(item -> {
                if (item.hasItem()) {
                    processGroupItem(item, questionnaireResponse, questionnaireCodeMap, resources, subject);
                } else {
                    processItem(item, questionnaireResponse, questionnaireCodeMap, resources, subject);
                }
            });
        }

        return resources;
    }

    private void processGroupItem(
            QuestionnaireResponseItemComponent item, QuestionnaireResponse questionnaireResponse,
            Map<String, List<Coding>> questionnaireCodeMap, List<IBaseResource> resources, Reference subject) {
        var subjectItems = item.getItem().stream().filter(child -> child.hasExtension(Constants.SDC_QUESTIONNAIRE_RESPONSE_IS_SUBJECT)).collect(Collectors.toList());
        var groupSubject = subjectItems.size() != 0 ? subjectItems.get(0).getAnswer().get(0).getValueReference() : subject.copy();
        var itemExtractionContext = item.getExtensionByUrl(Constants.SDC_QUESTIONNAIRE_ITEM_EXTRACTION_CONTEXT);
        if (itemExtractionContext != null) {
            processDefinitionItem(itemExtractionContext, item.getLinkId(), item.getItem(), questionnaireResponse, resources, groupSubject);
        } else {
            item.getItem().forEach(childItem -> {
                if (!childItem.hasExtension(Constants.SDC_QUESTIONNAIRE_RESPONSE_IS_SUBJECT)) {
                    if (childItem.hasItem()) {
                        processGroupItem(childItem, questionnaireResponse, questionnaireCodeMap, resources, groupSubject);
                    } else {
                        processItem(childItem, questionnaireResponse, questionnaireCodeMap, resources, groupSubject);
                    }
                }
            });
        }
    }

    private Enumerations.FHIRAllTypes getFhirType(Extension extension) {
        return Enumerations.FHIRAllTypes.fromCode(((CodeType) extension.getValue()).getCode());
    }

    private Property getSubjectProperty(Resource resource) {
        var property = resource.getNamedProperty("subject");
        if (property == null) {
            property = resource.getNamedProperty("patient");
        }

        return property;
    }

    private void processDefinitionItem(Extension itemExtractionContext, String linkId,
            List<QuestionnaireResponseItemComponent> items, QuestionnaireResponse questionnaireResponse,
            List<IBaseResource> resources, Reference subject) {
        // Definition-based extraction - http://build.fhir.org/ig/HL7/sdc/extraction.html#definition-based-extraction
        var resourceType = getFhirType(itemExtractionContext).toCode();
        var resource = (Resource) this.fhirContext.getResourceDefinition(resourceType).newInstance();
        resource.setId(new IdType(resourceType, "extract-" + questionnaireResponse.getIdElement().getIdPart() + "." + linkId));
        var subjectProperty = getSubjectProperty(resource);
        if (subjectProperty != null) {
            resource.setProperty(subjectProperty.getName(), subject);
        }
        items.forEach(childItem -> {
            if (childItem.hasDefinition()) {
                var definition = childItem.getDefinition().split("#");
                var path = definition[1];
                var pathElements = path.split("\\.");
                if (pathElements.length < 2) {
                    throw new RuntimeException(String.format("Unable to determine path from definition: %s", childItem.getDefinition()));
                }
                var answerValue = childItem.getAnswerFirstRep().getValue();
                if (answerValue != null) {
                    // First element is always the resource type, so it can be ignored
                    if (pathElements.length == 2) {
                        setProperty(resource, pathElements[1], answerValue);
                    } else {
                        processNestedItem(pathElements, resource, answerValue);
                    }
                }
            }
        });

        resources.add(resource);
    }

    private void processNestedItem(String[] pathElements, Base base, Type answerValue ) {
        var nestedPropertyName = pathElements[1];
        var nestedElements = new ArrayList<String>();
        for (int i = 2; i < pathElements.length; i++) {
            nestedElements.add(pathElements[i]);
        }
        var nestedProperty = base.getNamedProperty(nestedPropertyName);
        if (nestedProperty.getMaxCardinality() > 1 && nestedProperty.hasValues() && nestedProperty.getValues().size() > 1 ) {
            var newValues = nestedProperty.getValues();
        } else {
            var hasExisting = nestedProperty.hasValues();
            var newValue = hasExisting ? nestedProperty.getValues().get(0) : newValue(nestedProperty);
            if (nestedElements.size() == 1) {
                setProperty(newValue, pathElements[2], answerValue);
            } else {
                processNestedItem((String[]) nestedElements.toArray(), newValue, answerValue);
            }
            if (!hasExisting) {
                setProperty(base, nestedPropertyName, newValue);
            }
        }
    }

    private void setProperty(Base base, String propertyName, Base answerValue) {
        var property = base.getNamedProperty(propertyName);
        base.setProperty(propertyName, transformAnswerValue(answerValue, property));
    }

    private Base newValue(Property property) {
        try {
            var newValue = (Base) Class.forName("org.hl7.fhir.r4.model." + property.getTypeCode()).getConstructor().newInstance();

            return newValue;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private Base transformAnswerValue(Base answerValue, Property property) {
        if (answerValue.fhirType().equals(property.getTypeCode())) {
            return answerValue;
        }

        // TODO: Need to define each of these potential cases?
        if (answerValue.fhirType().equals("Coding") && property.getTypeCode().equals("code")) {
            return ((Coding)answerValue).getCodeElement();
        }

        throw new RuntimeException(String.format("Unable to transform answer of type (%s) to value of type (%s)", answerValue.fhirType(), property.getTypeCode()));
    }

    private void processItem(
            QuestionnaireResponseItemComponent item, QuestionnaireResponse questionnaireResponse,
            Map<String, List<Coding>> questionnaireCodeMap, List<IBaseResource> resources, Reference subject) {
        if (item.hasAnswer()) {
            item.getAnswer().forEach(answer -> {
                if (answer.hasItem()) {
                    answer.getItem().forEach(answerItem -> {
                        processItem(answerItem, questionnaireResponse, questionnaireCodeMap, resources, subject);
                    });
                } else {
                    if (questionnaireCodeMap.get(item.getLinkId()).size() > 0) {
                        resources.add(createObservationFromItemAnswer(answer, item.getLinkId(), questionnaireResponse, subject, questionnaireCodeMap));
                    }
                }
            });
        }
    }

    private Observation createObservationFromItemAnswer(
            QuestionnaireResponseItemAnswerComponent answer, String linkId, QuestionnaireResponse questionnaireResponse,
            Reference subject, Map<String, List<Coding>> questionnaireCodeMap) {
        // Observation-based extraction - http://build.fhir.org/ig/HL7/sdc/extraction.html#observation-based-extraction
        var obs = new Observation();
        obs.setId("extract-" + questionnaireResponse.getIdElement().getIdPart() + "." + linkId);
        obs.setBasedOn(questionnaireResponse.getBasedOn());
        obs.setPartOf(questionnaireResponse.getPartOf());
        obs.setStatus(Observation.ObservationStatus.FINAL);

        var qrCategoryCoding = new Coding();
        qrCategoryCoding.setCode("survey");
        qrCategoryCoding.setSystem("http://hl7.org/fhir/observation-category");
        obs.setCategory(Collections.singletonList(new CodeableConcept().addCoding(qrCategoryCoding)));

        obs.setCode(new CodeableConcept().setCoding(questionnaireCodeMap.get(linkId)));
        obs.setSubject(subject);
        // obs.setFocus();
        obs.setEncounter(questionnaireResponse.getEncounter());
        var authoredDate = new DateTimeType((questionnaireResponse.hasAuthored() ? questionnaireResponse.getAuthored().toInstant() : Instant.now()).toString());
        obs.setEffective(authoredDate);
        obs.setIssuedElement(new InstantType(authoredDate));
        obs.setPerformer(Collections.singletonList(questionnaireResponse.getAuthor()));

        switch (answer.getValue().fhirType()) {
            case "string":
                obs.setValue(new StringType(answer.getValueStringType().getValue()));
                break;
            case "Coding":
                obs.setValue(new CodeableConcept().addCoding(answer.getValueCoding()));
                break;
            case "boolean":
                obs.setValue(new BooleanType(answer.getValueBooleanType().booleanValue()));
                break;
        }
        var questionnaireResponseReference = new Reference();
        questionnaireResponseReference.setReference("QuestionnaireResponse/" + questionnaireResponse.getIdElement().getIdPart());
        obs.setDerivedFrom(Collections.singletonList(questionnaireResponseReference));

        var linkIdExtension = new Extension();
        linkIdExtension.setUrl("http://hl7.org/fhir/uv/sdc/StructureDefinition/derivedFromLinkId");
        var innerLinkIdExtension = new Extension();
        innerLinkIdExtension.setUrl("text");
        innerLinkIdExtension.setValue(new StringType(linkId));
        linkIdExtension.setExtension(Collections.singletonList(innerLinkIdExtension));
        obs.addExtension(linkIdExtension);

        return obs;
    }

//    private Bundle sendObservationBundle(Bundle observationsBundle) throws IllegalArgumentException {
//        String url = mySdcProperties.getExtract().getEndpoint();
//        if (null == url || url.length() < 1) {
//            throw new IllegalArgumentException(
//                    "Unable to transmit observation bundle.  No observation.endpoint defined in sdc properties.");
//        }
//        String user = mySdcProperties.getExtract().getUsername();
//        String password = mySdcProperties.getExtract().getPassword();
//
//        IGenericClient client = Clients.forUrl(fhirContext, url);
//        Clients.registerBasicAuth(client, user, password);
//        return client.transaction().withBundle(observationsBundle).execute();
//    }

    private Map<String, List<Coding>> getQuestionnaireCodeMap(String questionnaireUrl) {
//        String url = mySdcProperties.getExtract().getEndpoint();
//        if (null == url || url.length() < 1) {
//            throw new IllegalArgumentException("Unable to GET Questionnaire.  No observation.endpoint defined in sdc properties.");
//        }
//        String user = mySdcProperties.getExtract().getUsername();
//        String password = mySdcProperties.getExtract().getPassword();
//
//        IGenericClient client = Clients.forUrl(fhirContext, url);
//        Clients.registerBasicAuth(client, user, password);
//
//        Questionnaire questionnaire = client.read().resource(Questionnaire.class).withUrl(questionnaireUrl).execute();
        Questionnaire questionnaire = null;
        try {
            var results = this.fhirDal.searchByUrl("Questionnaire", questionnaireUrl);
            questionnaire = results == null ? null : (Questionnaire) results.iterator().next();
            if (questionnaire == null) {
                throw new RuntimeException();
            }
        } catch (Exception e) {
            logger.error(String.format("Error encountered searching for Questionnaire during extract operation: %s", e.getMessage()));
            throw new IllegalArgumentException(String.format("Unable to find resource by URL %s", questionnaireUrl));
        }

        return createCodeMap(questionnaire);
    }

    // this is based on "if a questionnaire.item has items then this item is a
    // header and will not have a specific code to be used with an answer"
    private Map<String, List<Coding>> createCodeMap(Questionnaire questionnaire) {
        var questionnaireCodeMap = new HashMap<String, List<Coding>>();
        questionnaire.getItem().forEach(item -> { processQuestionnaireItems(item, questionnaireCodeMap); });

        return questionnaireCodeMap;
    }

    private void processQuestionnaireItems(Questionnaire.QuestionnaireItemComponent item, Map<String, List<Coding>> questionnaireCodeMap) {
        if (item.hasItem()) {
            item.getItem().forEach(qItem -> { processQuestionnaireItems(qItem, questionnaireCodeMap); });
        } else {
            questionnaireCodeMap.put(item.getLinkId(), item.getCode());
        }
    }
}
