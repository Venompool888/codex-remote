import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('check', Path(__file__).with_name('check-compose-ui.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

class ComposeBoundaryTest(unittest.TestCase):
    def inspect(self, source):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'Screen.kt').write_text(source)
            return check.findings(root)
    def test_rejects_wrapping_legacy_view_as_a_migration(self):
        self.assertTrue(self.inspect('@Composable fun Screen() { AndroidView(factory = { EditText(it) }) }'))
    def test_rejects_fully_qualified_native_tree(self):
        self.assertTrue(self.inspect('fun screen(context: Context) = android.widget.LinearLayout(context)'))
    def test_permits_platform_host_and_real_compose(self):
        self.assertFalse(self.inspect('import android.widget.Toast\nclass MainActivity : ComponentActivity() {\n fun show() = setContent { Text("Hello") }\n}'))
    def test_permits_documentation_of_removed_legacy_ui(self):
        self.assertFalse(self.inspect('// Old code used AndroidView(factory = ...).\n/* import android.widget.TextView */\n@Composable fun Screen() = Text("Hi")'))

    def test_rejects_native_dialog_and_aliased_interop(self):
        for source in (
            'val dialog = android.app.AlertDialog.Builder(context).show()',
            'import android.widget.*',
            'onClick(View(context))',
            'import androidx.compose.ui.viewinterop.AndroidView as NativeHost',
        ):
            self.assertTrue(self.inspect(source))
    def test_permits_explicit_compose_radio_button(self):
        self.assertFalse(self.inspect('import androidx.compose.material3.RadioButton\nRadioButton(selected = true, onClick = {})'))
        self.assertTrue(self.inspect('import androidx.compose.material3.RadioButton\nandroid.widget.RadioButton(context)'))
    def test_keeps_original_source_line_numbers(self):
        result = self.inspect('/* Old UI\nremoved here */\nimport android.widget.TextView')
        self.assertEqual(3, result[0][1])

if __name__ == '__main__': unittest.main()
