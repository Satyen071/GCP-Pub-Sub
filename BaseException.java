package com.loblaw.dataflow.exception;

import com.loblaw.dataflow.code.ErrorData;
import lombok.Data;

@Data
public class BaseException extends RuntimeException {
    private static final long serialVersionUID = 6978786070633225764L;

    protected final ErrorData errorData;

    public BaseException(ErrorData errorData) {
        super(errorData != null ? errorData.getMessage() : "Something went wrong"); //ErrorData should never be null
        this.errorData = errorData;
    }

    public ErrorData getErrorData() {
        return this.errorData;
    }
}