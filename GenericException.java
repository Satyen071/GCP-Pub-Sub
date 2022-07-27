package com.loblaw.dataflow.exception;

import com.loblaw.dataflow.code.ErrorCode;
import com.loblaw.dataflow.code.ErrorData;

public class GenericException extends BaseException{

    public GenericException(ErrorCode errorCode, String message) {
        super(new ErrorData(errorCode, message));
    }
}
