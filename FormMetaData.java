package com.loblaw.ingestionservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormMetaData {

    private String organization;
    private String team;
    private String version;
    private String formName;
}
