"""How a service proves its identity to Keycloak (IAM-02, ADR-0017).

Preferred: a Kubernetes ServiceAccount token (a projected, short-lived, audience-bound JWT) sent as an RFC 7523
client assertion; no secret exists to leak or rotate. Fallback: a client secret from Key Vault (HTTP basic). The
assertion file is re-read on every request because the kubelet rotates it in place. Never log either value.
"""

from dataclasses import dataclass
from pathlib import Path

ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"


@dataclass(frozen=True)
class ClientAuth:
    client_id: str
    client_secret: str = ""
    assertion_file: str = ""  # path of the projected ServiceAccount token; takes precedence over the secret

    def apply(self, data: dict[str, str]) -> tuple[dict[str, str], tuple[str, str] | None]:
        """Return (form data, basic-auth credentials or None) for a token request."""
        if self.assertion_file:
            assertion = Path(self.assertion_file).read_text(encoding="utf-8").strip()
            form = {
                **data,
                "client_id": self.client_id,
                "client_assertion_type": ASSERTION_TYPE,
                "client_assertion": assertion,
            }
            return form, None
        return data, (self.client_id, self.client_secret)
