package mtgbench.fetch;

/** A deck or index could not be downloaded; the message is meant for the user. */
public final class FetchException extends Exception {
    public FetchException(String message) {
        super(message);
    }
}
