package ie.deri.unlp.javaservices.documentindex;

public class IndexingException extends Exception {
	private static final long serialVersionUID = 4610822145163690000L;

	public IndexingException(String message) {
		super(message);
	}

	public IndexingException(String message, Exception e) {
		super(message, e);
	}
}