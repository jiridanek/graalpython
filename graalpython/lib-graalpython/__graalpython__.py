# Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.


@builtin
def import_current_as_named_module(module_name, globals):
    """
    load a builtin anonymous module which does not have a Truffle land builtin module counter part

    :param module_name: the module name to load as
    :param globals: the module globals (to update)
    :return: None
    """
    import sys
    module = sys.modules.get(module_name, None)
    if not module:
        module = type(sys)(module_name)
        sys.modules[module_name] = module
    new_globals = dict(**globals)
    new_globals.update(**module.__dict__)
    module.__dict__.update(**new_globals)


@builtin
def lazy_attribute_loading_from_module(attributes, module_name, globals):
    """
    used to lazily load attributes defined in another module via the __getattr__ mechanism.
    This will only cache the attributes in the caller module.

    :param attributes: a list of attributes names to be loaded lazily from the delagate module
    :param module_name: the delegate module
    :param globals: the module globals (to update)
    :return:
    """
    def __getattr__(name):
        if name in attributes:
            delegate_module = __import__(module_name)
            globals.update(delegate_module.__dict__)
            globals['__all__'] = attributes
            if '__getattr__' in globals:
                del globals['__getattr__']
            return getattr(delegate_module, name)
        raise AttributeError("module '{}' does not have '{}' attribute".format(module_name, name))

    globals['__getattr__'] = __getattr__
    globals['__all__'] = ['__getattr__']
