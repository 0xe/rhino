package org.mozilla.javascript.tests.es6;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.drivers.LanguageVersion;
import org.mozilla.javascript.drivers.RhinoTest;
import org.mozilla.javascript.drivers.ScriptTestsBase;

@RhinoTest("testsrc/jstests/es6/stringify-xml.js")
@LanguageVersion(Context.VERSION_ES6)
public class StringifyXmlTest extends ScriptTestsBase {}
