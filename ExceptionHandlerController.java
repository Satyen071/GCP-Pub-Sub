package com.loblaw.ingestionservice.controller;

import com.loblaw.ingestionservice.dto.ExceptionResponse;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

@ControllerAdvice
@RestController
@Slf4j
public class ExceptionHandlerController {
    @ExceptionHandler(IOException.class)
    public final ResponseEntity<Object> iOExceptionHandler(Exception exception, WebRequest webRequest){
        String exceptionMessage = exception.getMessage();
        if(Objects.isNull(exception.getMessage())){
            exceptionMessage = "IO Exception Occurred";
        }
        ExceptionResponse exceptionResponse = new ExceptionResponse(
                new Date(),
                exceptionMessage,
                webRequest.getDescription(false),
                400
                );
        log.error(exceptionMessage,exception);
        return new ResponseEntity<>(exceptionResponse, HttpStatus.BAD_REQUEST);
    }
    @ExceptionHandler(ExecutionException.class)
    public final ResponseEntity<Object> executionExceptionHandler(Exception exception, WebRequest webRequest){
        String exceptionMessage = exception.getMessage();
        if(Objects.isNull(exception.getMessage())){
            exceptionMessage = "Execution Exception Occurred";
        }
        ExceptionResponse exceptionResponse = new ExceptionResponse(
                new Date(),
                exceptionMessage,
                webRequest.getDescription(false),
                400
        );
        log.error(exceptionMessage,exception);
        return new ResponseEntity<>(exceptionResponse, HttpStatus.BAD_REQUEST);

    }
    @ExceptionHandler(InterruptedException.class)
    public final ResponseEntity<Object> interruptedExceptionHandler(Exception exception, WebRequest webRequest){
        String exceptionMessage = exception.getMessage();
        if(Objects.isNull(exception.getMessage())){
            exceptionMessage = "Interrupted Exception Occurred";
        }
        ExceptionResponse exceptionResponse = new ExceptionResponse(
                new Date(),
                exceptionMessage,
                webRequest.getDescription(false),
                409
        );
        log.error(exceptionMessage,exception);
        return new ResponseEntity<>(exceptionResponse, HttpStatus.CONFLICT);

    }
    @ExceptionHandler(JSONException.class)
    public final ResponseEntity<Object> invalidJsonExceptionHandler(Exception exception, WebRequest webRequest){
        String exceptionMessage = exception.getMessage();
        if(Objects.isNull(exception.getMessage())){
            exceptionMessage = "Invalid Json in Request body";
        }
        ExceptionResponse exceptionResponse = new ExceptionResponse(
                new Date(),
                exceptionMessage,
                webRequest.getDescription(false),
                400
        );
        log.error(exceptionMessage,exception);
        return new ResponseEntity<>(exceptionResponse, HttpStatus.BAD_REQUEST);

    }
}
