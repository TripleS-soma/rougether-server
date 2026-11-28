import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("export_settings",ROOT/"export-cutover-settings.py")
settings=importlib.util.module_from_spec(spec)
spec.loader.exec_module(settings)


class ExportSettingsTest(unittest.TestCase):
    def test_host_and_runner_share_validation(self):
        src=(ROOT/"export-cutover-settings.py").read_text()
        code=src[src.index("BOOL = "):src.index("\n\ndef export(")].rstrip()
        self.assertIn(code,(ROOT/"deploy-ec2-with-rollback.sh").read_text())

    def test_only_public_fields_round_trip(self):
        raw={"MARKET_ENGINE_ENABLED":"TRUE","INVITE_APPLE_APP_ID":"123456789A.com.triples.rougether",
             "INVITE_SHARE_BASE_URL":"https://rougether.com","APPLE_PRIVATE_KEY":"TEST-SECRET"}
        flags=settings.validated(raw)
        result=settings.export("ROUGETHER_SOURCE_FEATURES="+json.dumps(flags),"e"*40,"12")
        self.assertEqual("true",result["userApiSettings"]["MARKET_ENGINE_ENABLED"])
        self.assertNotIn("TEST-SECRET",json.dumps(result))
        for field,value in [("INVITE_SHARE_BASE_URL","https://rougether.com/?token=secret"),
                            ("BILLING_ENABLED","yes"),("INVITE_ANDROID_CERT_FINGERPRINTS","aa:bb")]:
            with self.assertRaises(ValueError):
                settings.validated({field:value})

    def test_no_silent_loss_or_duplicate_records(self):
        for text in ["", "ROUGETHER_SOURCE_FEATURES={}\nROUGETHER_SOURCE_FEATURES={}",
                     'ROUGETHER_SOURCE_FEATURES={"OTHER":"unreviewed"}']:
            with self.assertRaises(ValueError):
                settings.export(text,"a"*40,"123")

if __name__=="__main__":
    unittest.main()
