package com.loblaw.dataflow.code;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.loblaw.dataflow.code.ErrorCode;
import lombok.Value;

@Value
public class ErrorData implements Serializable {
    ErrorCode code;
    String message;
    transient Map<String, Object> data;

    public ErrorData(ErrorCode code, Map<String, Object> data) {
        this.code = code;
        this.message = code.getDefaultErrorMessage();
        this.data = data != null ? data : new HashMap<>();
    }

    public ErrorData(ErrorCode code, String message) {
        this.code = code;
        this.message = message.isEmpty() ? code.getDefaultErrorMessage() : message;
        this.data = new HashMap<>();
    }
}
