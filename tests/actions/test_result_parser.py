import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('parser', Path(__file__).resolve().parents[2] / 'scripts/actions/check-instrumentation.py')
parser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parser)


class InstrumentationResultTest(unittest.TestCase):
    def test_success_requires_real_terminal(self):
        self.assertEqual(parser.inspect('OK (12 checks)\nINSTRUMENTATION_CODE: -1\n', 0)['status'], 'passed')

    def test_partial_or_failed_never_pass(self):
        cases = [('', 0), ('PASS something\n', 0), ('OK (12 checks)\n', 0),
                 ('OK (12 checks)\nINSTRUMENTATION_CODE: 0\n', 0),
                 ('OK (12 checks)\nINSTRUMENTATION_CODE: -1\n', 124),
                 ('OK (0 checks)\nINSTRUMENTATION_CODE: -1\n', 0),
                 ('OK (12 checks)\nFAIL later\nINSTRUMENTATION_CODE: -1\n', 0),
                 ('OK (12 checks)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0\n', 0),
                 ('OK (12 checks)\nINSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: -1\n', 0)]
        for text, code in cases:
            with self.subTest(text=text, code=code):
                self.assertEqual(parser.inspect(text, code)['status'], 'failed_or_incomplete')


if __name__ == '__main__':
    unittest.main()
