{{- define "shp.labels" -}}
app.kubernetes.io/part-of: shp-local-deps
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
