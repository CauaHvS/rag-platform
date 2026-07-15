{{/*
Expand the name of the chart.
*/}}
{{- define "rag-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "rag-platform.fullname" -}}
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
Chart label value (name-version).
*/}}
{{- define "rag-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels (aplicados a todos os recursos).
*/}}
{{- define "rag-platform.labels" -}}
helm.sh/chart: {{ include "rag-platform.chart" . }}
{{ include "rag-platform.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels (usados em Deployment.spec.selector e Service.spec.selector).
*/}}
{{- define "rag-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "rag-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Selector labels específicos por componente.
*/}}
{{- define "rag-platform.app.selectorLabels" -}}
{{ include "rag-platform.selectorLabels" . }}
app.kubernetes.io/component: app
{{- end }}

{{- define "rag-platform.frontend.selectorLabels" -}}
{{ include "rag-platform.selectorLabels" . }}
app.kubernetes.io/component: frontend
{{- end }}

{{- define "rag-platform.postgres.selectorLabels" -}}
{{ include "rag-platform.selectorLabels" . }}
app.kubernetes.io/component: postgres
{{- end }}

{{/*
Nome do ServiceAccount.
*/}}
{{- define "rag-platform.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "rag-platform.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Nome do serviço do app backend.
*/}}
{{- define "rag-platform.app.serviceName" -}}
{{- printf "%s-app" (include "rag-platform.fullname" .) }}
{{- end }}

{{/*
Nome do serviço do PostgreSQL.
*/}}
{{- define "rag-platform.postgres.serviceName" -}}
{{- printf "%s-postgres" (include "rag-platform.fullname" .) }}
{{- end }}

{{/*
Nome do serviço do Redis (bitnami subchart).
*/}}
{{- define "rag-platform.redis.serviceName" -}}
{{- printf "%s-redis-master" .Release.Name }}
{{- end }}
