package fi.csc.chipster.sessionworker.xml;

public class DataBean {

	/**
	 * Link represents a relationship between two beans.
	 */
	public enum Link {
		/**
		 * Relationship where one bean describes (acts as metadata for) another.
		 */
		ANNOTATION("Annotation"),

		/**
		 * Relationship where other bean has been used to derive another (i.e. beans are
		 * an input and an output of an operation).
		 */
		DERIVATION("Derivation"),

		/**
		 * Relationship where other bean has been modified into another (i.e. user has
		 * modified and saved a new copy of a bean).
		 * Philosophically modification is a special case of derivation, but they are
		 * modelled as two different types to separate manual (modification) and
		 * automatic production (derivation) of beans.
		 */
		MODIFICATION("Modification"),

		/**
		 * Relationship where two beans belong to a same group.
		 */
		GROUPING("Grouping");

		private String name;

		private Link(String name) {
			this.name = name;
		}

		public String toString() {
			return this.name;
		}

		public static Link[] userEditableValues() {
			return new Link[] { ANNOTATION, DERIVATION, MODIFICATION };
		}

		public static Link[] derivationalTypes() {
			return new Link[] { DERIVATION, MODIFICATION };
		}
	}
}