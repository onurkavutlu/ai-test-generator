package com.testgen.service;

public class TestGenerationException extends RuntimeException {
    public TestGenerationException(String message) { super(message); }
    public TestGenerationException(String message, Throwable cause) { super(message, cause); }
}
