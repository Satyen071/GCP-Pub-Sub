package com.loblaw.dataflow.exception;

import com.loblaw.dataflow.code.ErrorCode;

public class ExceptionFactory {

    private ExceptionFactory() {
    }

    public static GenericException getGenericException(ErrorCode errorCode, String data) {
        return new GenericException(errorCode, data);
    }


}
