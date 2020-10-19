from .. import abc
from .. import util

machinery = util.import_importlib('importlib.machinery')

import unittest
from test.support import impl_detail


class FindSpecTests(abc.FinderTests):

    """Test finding frozen modules."""

    def find(self, name, path=None):
        finder = self.machinery.FrozenImporter
        return finder.find_spec(name, path)

    @impl_detail("GR-26392: add support for frozen modules", graalvm=False)
    def test_module(self):
        name = '__hello__'
        spec = self.find(name)
        self.assertEqual(spec.origin, 'frozen')

    @impl_detail("GR-26392: add support for frozen modules", graalvm=False)
    def test_package(self):
        spec = self.find('__phello__')
        self.assertIsNotNone(spec)

    @impl_detail("GR-26392: add support for frozen modules", graalvm=False)
    def test_module_in_package(self):
        spec = self.find('__phello__.spam', ['__phello__'])
        self.assertIsNotNone(spec)

    # No frozen package within another package to test with.
    test_package_in_package = None

    # No easy way to test.
    test_package_over_module = None

    def test_failure(self):
        spec = self.find('<not real>')
        self.assertIsNone(spec)


(Frozen_FindSpecTests,
 Source_FindSpecTests
 ) = util.test_both(FindSpecTests, machinery=machinery)


class FinderTests(abc.FinderTests):

    """Test finding frozen modules."""

    def find(self, name, path=None):
        finder = self.machinery.FrozenImporter
        return finder.find_module(name, path)

    @impl_detail("GR-26392: add support for frozen modules", graalvm=False)
    def test_module(self):
        name = '__hello__'
        loader = self.find(name)
        self.assertTrue(hasattr(loader, 'load_module'))

    @impl_detail("GR-26392: add support for frozen modules", graalvm=False)
    def test_package(self):
        loader = self.find('__phello__')
        self.assertTrue(hasattr(loader, 'load_module'))

    @impl_detail("GR-26392: add support for frozen modules", graalvm=False)
    def test_module_in_package(self):
        loader = self.find('__phello__.spam', ['__phello__'])
        self.assertTrue(hasattr(loader, 'load_module'))

    # No frozen package within another package to test with.
    test_package_in_package = None

    # No easy way to test.
    test_package_over_module = None

    def test_failure(self):
        loader = self.find('<not real>')
        self.assertIsNone(loader)


(Frozen_FinderTests,
 Source_FinderTests
 ) = util.test_both(FinderTests, machinery=machinery)


if __name__ == '__main__':
    unittest.main()
