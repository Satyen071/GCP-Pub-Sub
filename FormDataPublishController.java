package com.loblaw.ingestionservice.controller;

import com.loblaw.ingestionservice.dto.FormInputData;
import com.loblaw.ingestionservice.dto.FormMetaData;
import com.loblaw.ingestionservice.service.PublisherService;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping(
        path = "/api/v1"
)
public class FormDataPublishController {

    @Autowired
    PublisherService publisherService;
    @Timed(histogram = true)
    @PostMapping("/form")
    public ResponseEntity<String> publishMessage(@RequestHeader String organization,
                                                 @RequestHeader String team,
                                                 @RequestHeader String formName,
                                                 @RequestHeader String version,
                                                 @RequestBody String formData) throws JSONException{

        JSONObject jsonObject = new JSONObject(formData);
        FormMetaData formMetaData = new FormMetaData(organization,team,version,formName);
        FormInputData formInputData= new FormInputData(jsonObject,formMetaData);
        publisherService.messagePublisher(formInputData);
        return new ResponseEntity<>(HttpStatus.OK);
    }
}
