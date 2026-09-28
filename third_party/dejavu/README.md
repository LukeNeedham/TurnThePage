# DejaVu Sans in the PDF test fixture

The instrumentation-test fixture
[`app/src/androidTest/assets/v11/sample.pdf`](../../app/src/androidTest/assets/v11/sample.pdf)
embeds a subset of DejaVu Sans 2.37, named `AAAAAA+DejaVuSans` in the PDF.
This subset is present only in that test fixture; it is not bundled in the
Listai application.

[NOTICE.txt](NOTICE.txt) preserves the copyright and full license text extracted
from the embedded TrueType font's `name` table: name ID 0 followed by name ID 13.
The font data is the Flate-compressed `FontFile2` stream in PDF object 8.
The notice includes both the Bitstream Vera and Arev terms and states that
DejaVu changes are in the public domain. The original test PDF is unchanged.

The font's embedded license URL is
<http://dejavu.sourceforge.net/wiki/index.php/License>.
The current upstream project is <https://dejavu-fonts.github.io/>.
