import unittest
from check_release_identity import check_identity


class ReleaseIdentityTest(unittest.TestCase):
    baseline = {"applicationId": "app.ultraslim", "publishedVersionCode": 23, "certificateSha256": "a" * 64}
    source = 'versionCode = 24\nversionName = "1.0.1"\n'

    def check(self, signer="a", package="app.ultraslim", code=24, name="1.0.1"):
        return check_identity(f"Signer #1 certificate SHA-256 digest: {signer * 64}\n",
                              f"package: name='{package}' versionCode='{code}' versionName='{name}'\n",
                              self.baseline, self.source)

    def test_accepts_next_release(self):
        self.assertEqual(self.check(), "app.ultraslim 1.0.1 (24)")

    def test_rejects_other_valid_signer(self):
        with self.assertRaisesRegex(ValueError, "certificate"):
            self.check(signer="b")

    def test_rejects_debug_package(self):
        with self.assertRaisesRegex(ValueError, "application ID"):
            self.check(package="app.ultraslim.debug")

    def test_rejects_old_or_repeated_version(self):
        for code in (22, 23):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "exceed"):
                self.check(code=code)

    def test_rejects_stale_apk_version(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.check(code=25)
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.check(name="1.0.0")

    def test_rejects_missing_or_additional_signers(self):
        for certificate in ("", "Signer #1 certificate SHA-256 digest: " + "a" * 64 +
                            "\nSigner #2 certificate SHA-256 digest: " + "b" * 64 + "\n"):
            with self.subTest(certificate=certificate), self.assertRaisesRegex(ValueError, "certificate"):
                check_identity(certificate, "", self.baseline, self.source)


if __name__ == "__main__":
    unittest.main()
