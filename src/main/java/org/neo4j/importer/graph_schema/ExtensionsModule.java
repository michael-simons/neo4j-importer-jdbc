package org.neo4j.importer.graph_schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

public class ExtensionsModule extends GraphSchemaModule {
	public ExtensionsModule() {
		setMixInAnnotation(Extensions.KeyProperty.class, ExtensionsModule.KeyPropertyMixin.class);
	}

	private abstract static class KeyPropertyMixin {

		@JsonCreator
		KeyPropertyMixin(
			GraphSchema.Ref node,
			@JsonProperty("keyProperty")
			GraphSchema.Ref property
		) {
		}
	}
}
