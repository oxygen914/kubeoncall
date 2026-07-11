{{- define "kubeoncall.name" -}}kubeoncall{{- end }}
{{- define "kubeoncall.fullname" -}}{{ .Release.Name }}-kubeoncall{{- end }}
{{- define "kubeoncall.labels" -}}
app.kubernetes.io/name: {{ include "kubeoncall.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
