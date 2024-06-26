/* -*- Mode: javascript; tab-width: 2; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

var gTestfile = 'regress-416737-02.js';
//-----------------------------------------------------------------------------
var BUGNUMBER = 416737;
var summary = 'Do not assert: *pc == JSOP_GETARG';
var actual = '';
var expect = '';


//-----------------------------------------------------------------------------
test();
//-----------------------------------------------------------------------------

function test()
{
  enterFunc ('test');
  printBugNumber(BUGNUMBER);
  printStatus (summary);

  var f = function([]){ function n(){} };
  if (typeof dis == 'function')
  {
    dis(f);
  }
  print(f);

  reportCompare(expect, actual, summary);

  exitFunc ('test');
}
