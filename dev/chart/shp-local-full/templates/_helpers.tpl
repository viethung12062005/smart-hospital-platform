{{/* Service trong cụm cho một thành phần. Tham số: name, port, portName. */}}
{{- define "shp.svc" -}}
apiVersion: v1
kind: Service
metadata: { name: {{ .name }} }
spec:
  selector: { app: {{ .name }} }
  ports: [{ name: {{ .portName }}, port: {{ .port }} }]
{{- end }}

{{/* Thêm Service NodePort để mở ra localhost (cổng khớp dev/kind/cluster.yaml). Tham số: name, port, portName, nodePort. */}}
{{- define "shp.svc.host" -}}
apiVersion: v1
kind: Service
metadata: { name: {{ .name }}-host }
spec:
  type: NodePort
  selector: { app: {{ .name }} }
  ports: [{ name: {{ .portName }}, port: {{ .port }}, targetPort: {{ .port }}, nodePort: {{ .nodePort }} }]
{{- end }}
