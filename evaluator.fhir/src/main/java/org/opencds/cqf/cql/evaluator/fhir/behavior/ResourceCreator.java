package org.opencds.cqf.cql.evaluator.fhir.behavior;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.opencds.cqf.cql.evaluator.fhir.util.Ids;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public interface ResourceCreator extends FhirContextUser {
	@SuppressWarnings("unchecked")
	default <T extends IBaseResource, I extends IIdType> T newResource(I theId) {
		checkNotNull(theId, "theId is required");
		checkArgument(theId.getResourceType() != null, "theId must have a resourceType");

		IBaseResource newResource = this.getFhirContext().getResourceDefinition(theId.getResourceType()).newInstance();
		newResource.setId(theId);
		return (T) newResource;
	}

	default <T extends IBaseResource> T newResource(Class<T> theResourceClass, String theIdPart) {
		checkNotNull(theResourceClass);
		checkNotNull(theIdPart);

		T newResource = newResource(theResourceClass);
		IIdType id = Ids.newId(getFhirContext(), newResource.fhirType(), theIdPart);
		newResource.setId(id);

		return newResource;
	}

	@SuppressWarnings("unchecked")
	default <T extends IBaseResource> T newResource(Class<T> theResourceClass) {
		checkNotNull(theResourceClass);

		return (T) this.getFhirContext().getResourceDefinition(theResourceClass).newInstance();
	}

	@SuppressWarnings("unchecked")
	default <T extends IBaseResource> T newResource(String theResourceType) {
		checkNotNull(theResourceType);

		return (T) this.getFhirContext().getResourceDefinition(theResourceType).newInstance();
	}
}
