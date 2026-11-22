"""Incremental reader of the model's JSON reply (AI-09).

The model returns one JSON object (`{"answered": true, "answer": "... [1] ...", "citations": [...]}`). Only the
`answer` string is user-facing, so the stream shows the characters of that string as they arrive and never the JSON
scaffolding or the citation list. The reply is still validated as a whole when it ends (guardrails.check_output):
text shown here is provisional until then and is retracted with a `reset` event if the check fails.
"""

import re

_KEY = re.compile(r'"answer"\s*:\s*"')
_SIMPLE = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t"}
_HEX = re.compile(r"[0-9a-fA-F]{4}")


class AnswerExtractor:
    """Feed raw reply chunks, get the decoded `answer` text that became available. Chunks may split a JSON escape
    (`\\` + `n`, `\\u00` + `e9`) or a surrogate pair: such a tail is held until it is complete."""

    def __init__(self) -> None:
        self._raw = ""
        self._in_answer = False
        self._done = False
        self.text = ""  # everything emitted so far

    def feed(self, chunk: str) -> str:
        if self._done:
            return ""
        self._raw += chunk
        if not self._in_answer:
            m = _KEY.search(self._raw)
            if m is None:
                self._raw = self._raw[-16:]  # the key could still be arriving; nothing older can matter
                return ""
            self._raw = self._raw[m.end() :]
            self._in_answer = True
        out: list[str] = []
        i = 0
        raw = self._raw
        while i < len(raw):
            c = raw[i]
            if c == '"':
                self._done = True
                i = len(raw)
                break
            if c != "\\":
                out.append(c)
                i += 1
                continue
            if i + 1 >= len(raw):
                break
            kind = raw[i + 1]
            if kind in _SIMPLE:
                out.append(_SIMPLE[kind])
                i += 2
            elif kind == "u":
                if i + 6 > len(raw):
                    break
                code = _unicode(raw[i + 2 : i + 6])
                if code is None:
                    out.append("�")
                    i += 2
                elif 0xD800 <= code < 0xDC00:  # high surrogate: needs the low one
                    if i + 12 > len(raw):
                        break
                    low = _unicode(raw[i + 8 : i + 12]) if raw[i + 6 : i + 8] == "\\u" else None
                    if low is not None and 0xDC00 <= low < 0xE000:
                        out.append(chr(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)))
                        i += 12
                    else:
                        out.append("�")
                        i += 6
                else:
                    out.append(chr(code))
                    i += 6
            else:
                out.append(kind)
                i += 2
        self._raw = "" if self._done else raw[i:]
        text = "".join(out)
        self.text += text
        return text


def _unicode(hex4: str) -> int | None:
    return int(hex4, 16) if _HEX.fullmatch(hex4) else None
