`password-protected.pdf` is a generated one-page blank PDF, encrypted with the test password
`listai-test-password` using RC4-128. It contains no third-party book content.

Reproduce with pypdf:

```python
from pypdf import PdfWriter
writer = PdfWriter()
writer.add_blank_page(width=400, height=600)
writer.encrypt("listai-test-password", algorithm="RC4-128")
with open("password-protected.pdf", "wb") as output:
    writer.write(output)
```
