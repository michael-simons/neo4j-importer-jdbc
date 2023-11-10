package org.neo4j.importer.graph_schema;

import java.util.List;
import java.util.Map;

import org.neo4j.importer.graph_schema.GraphSchema.Ref;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class Extensions {

	private List<KeyProperty> nodeKeyProperties;

	@JsonCreator
	Extensions(@JsonProperty("nodeKeyProperties") List<KeyProperty> nodeKeyProperties) {
		this.nodeKeyProperties = nodeKeyProperties;
	}

	public List<KeyProperty> getNodeKeyProperties() {
		return nodeKeyProperties;
	}

	public record KeyProperty(Ref node, Ref property) {
	}



	private Extensions() {
	}
}
