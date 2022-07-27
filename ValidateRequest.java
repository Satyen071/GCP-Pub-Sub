package com.loblaw.dataflow.validation;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;


@Data
public class ValidateRequest {

    public  void validateRequestHeaders(String organization,String team, String formName, String version) throws HeaderException {

        String errorMessage = "";

        if(StringUtils.isEmpty(organization)){
            errorMessage="Empty or Null value for header: organization";
            throw new HeaderException(errorMessage);
        }
        if(StringUtils.isEmpty(team)){
            errorMessage="Empty or Null value for header: team";
            throw new HeaderException(errorMessage);
        }
        if(StringUtils.isEmpty(formName)){
            errorMessage="Empty or Null value for header: formName";
            throw new HeaderException(errorMessage);
        }
        if(StringUtils.isEmpty(version)){
            errorMessage="Empty or Null value for header: version";
            throw new HeaderException(errorMessage);
        }

    }

    public  void validateRequestBody(JSONObject formData) throws RequestBodyException {

        if(formData.length() == 0){
            throw new RequestBodyException("FormData is empty");
        }

    }
}
