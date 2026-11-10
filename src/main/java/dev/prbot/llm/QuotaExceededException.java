package dev.prbot.llm;

/** The daily model request cap is used up; the review is skipped, not failed. */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
