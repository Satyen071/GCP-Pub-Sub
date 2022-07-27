package com.loblaw.dataflow.code;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "File not found"),
    INTERRUPTED(HttpStatus.NOT_FOUND, "Table not exist"),
    UNKNOWN_CODE(HttpStatus.BAD_REQUEST, "Something went wrong"),
    DLP_ERROR(HttpStatus.BAD_REQUEST, "Something went wrong with DLP transformation");

    private final HttpStatus httpCode;
    private final String defaultErrorMessage;

    ErrorCode(HttpStatus httpCode, String message) {
        this.httpCode = httpCode;
        this.defaultErrorMessage = message;
    }

    public HttpStatus getHttpCode() {
        return httpCode;
    }

    public String getDefaultErrorMessage() {
        return defaultErrorMessage;
    }
}
