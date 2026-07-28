{{- define "kubeoncall.name" -}}kubeoncall{{- end }}
{{- define "kubeoncall.fullname" -}}{{ .Release.Name }}-kubeoncall{{- end }}
{{- define "kubeoncall.labels" -}}
app.kubernetes.io/name: {{ include "kubeoncall.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "kubeoncall.imageRef" -}}
{{- $tag := default .root.Chart.AppVersion .image.tag -}}
{{- if .image.digest -}}
{{ printf "%s@%s" .image.repository .image.digest }}
{{- else -}}
{{ printf "%s:%s" .image.repository $tag }}
{{- end -}}
{{- end }}

{{- define "kubeoncall.sandboxCatalogName" -}}
{{- default (printf "%s-sandbox-tools" (include "kubeoncall.fullname" .)) .Values.sandboxTools.catalog.name -}}
{{- end }}

{{/*
Spring Boot requires bracket notation for map keys containing '/'. Keep values.yaml ergonomic and
translate the successor map only at the environment-binding boundary.
*/}}
{{- define "kubeoncall.legacyApiSpringJson" -}}
{{- $successors := dict -}}
{{- range $path, $successor := .Values.legacyApi.successors -}}
{{- $key := $path -}}
{{- if not (and (hasPrefix "[" $path) (hasSuffix "]" $path)) -}}
{{- $key = printf "[%s]" $path -}}
{{- end -}}
{{- $_ := set $successors $key $successor -}}
{{- end -}}
{{- dict "kubeoncall" (dict "legacy-api" (dict "successors" $successors "retired-endpoints" .Values.legacyApi.retiredEndpoints)) | toJson -}}
{{- end }}
