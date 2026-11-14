{{/* In-cluster Service for one component. Parameters: name, port, portName. */}}
{{- define "shp.svc" -}}
apiVersion: v1
kind: Service
metadata: { name: {{ .name }} }
spec:
  selector: { app: {{ .name }} }
  ports: [{ name: {{ .portName }}, port: {{ .port }} }]
{{- end }}

{{/* Add a NodePort Service to expose on localhost (ports match dev/kind/cluster.yaml). Parameters: name, port, portName, nodePort. */}}
{{- define "shp.svc.host" -}}
apiVersion: v1
kind: Service
metadata: { name: {{ .name }}-host }
spec:
  type: NodePort
  selector: { app: {{ .name }} }
  ports: [{ name: {{ .portName }}, port: {{ .port }}, targetPort: {{ .port }}, nodePort: {{ .nodePort }} }]
{{- end }}
