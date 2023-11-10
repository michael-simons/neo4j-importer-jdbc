
package org.neo4j.importer.graph_schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The schema derived.
 */
public final class GraphSchema {

	/**
	 * Map from label (string value) to token.
	 */
	private final Map<String, Token> nodeLabels;
	/**
	 * Map from type (string value) to token.
	 */
	private final Map<String, Token> relationshipTypes;
	/**
	 * Map from generated ID to instance.
	 */
	private final Map<Ref, NodeObjectType> nodeObjectTypes;
	/**
	 * Map from generated ID to instance.
	 */
	private final Map<Ref, RelationshipObjectType> relationshipObjectTypes;

	GraphSchema(Map<String, Token> nodeLabels, Map<String, Token> relationshipTypes, Map<Ref, NodeObjectType> nodeObjectTypes, Map<Ref, RelationshipObjectType> relationshipObjectTypes) {
		this.nodeLabels = nodeLabels;
		this.relationshipTypes = relationshipTypes;
		this.nodeObjectTypes = nodeObjectTypes;
		this.relationshipObjectTypes = relationshipObjectTypes;
	}

	public Map<String, Token> nodeLabels() {
		return nodeLabels;
	}

	public Map<String, Token> relationshipTypes() {
		return relationshipTypes;
	}

	public Map<Ref, NodeObjectType> nodeObjectTypes() {
		return nodeObjectTypes;
	}

	public Map<Ref, RelationshipObjectType> relationshipObjectTypes() {
		return relationshipObjectTypes;
	}

	public record Type(String value, String itemType) {
	}

	public record Property(String id, String token, List<Type> types, boolean mandatory) {
	}

	public record NodeObjectType(String id, List<Ref> labels, List<Property> properties) implements HasProperties {
	}

	public record Token(String id, String value) {
	}

	public record Ref(String value) {
	}

	public record RelationshipObjectType(String id, Ref type, Ref from, Ref to, List<GraphSchema.Property> properties) implements HasProperties{
	}

	public interface HasProperties {
		List<GraphSchema.Property> properties();

		default Optional<Property> propertyBy(Ref ref) {
			return properties().stream().filter(p -> ref.value().equals(p.id()))
				.findFirst();
		}
	}
}
