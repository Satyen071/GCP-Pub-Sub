{{/*
Expand the name of the chart.
*/}}
{{- define "form.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "form.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}


{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "form.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}


{{/*
Common labels
*/}}
{{- define "form.labels" -}}
helm.sh/chart: {{ include "form.chart" . }}
{{ include "form.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}


{{/*
Selector labels
*/}}
{{- define "form.selectorLabels" -}}
app.kubernetes.io/name: {{ include "form.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* 
App labels
*/}}
{{- define "form.applabel" -}}
{{- if .Values.app }}
app: {{ .Values.app | trimSuffix "-" | trunc 63 }}
{{- else }}
app: {{ .Chart.Name | trunc 63 }}
{{- end }}
{{- end }}


{{/*
Secrets for SA
*/}}
{{- define "form.secret" -}}
{{- if .Values.secrets.name -}}
{{- .Values.secrets.name }}
{{- else }}
{{- $sec := default .Values.deployment.app  .Values.secrets.name }}
{{- printf "%s-%s" $sec "secret" }}
{{- end }}
{{- end }}


{{/*
Secrets Volumes
*/}}
{{- define "form.volume" -}}
- name: {{ .Values.volumes.name }}
  secret:
    secretName: {{ include "form.secret" . }}
    defaultMode: 420
{{- end }}

{{/*
Victoria metrics annotations
*/}}
{{- define "form.victoriametrics" -}}
prometheus.io/scrape: "true"
{{ with (index .Values.prometheus.podMonitor.podMetricsEndpoints 0 ) -}}
prometheus.io/port: {{ .port }}
{{- end }}
{{- end }}


