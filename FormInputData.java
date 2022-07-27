package com.loblaw.ingestionservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.json.JSONObject;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FormInputData {

    private JSONObject formData;
    private FormMetaData formMetaData;
}
