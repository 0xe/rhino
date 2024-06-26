/* -*- Mode: javascript; tab-width: 2; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

var gTestfile = 'regress-433279-03.js';
//-----------------------------------------------------------------------------
var BUGNUMBER = 433279;
var summary = 'Do not assert: pn != tc->parseContext->nodeList';
var actual = 'No Crash';
var expect = 'No Crash';

//-----------------------------------------------------------------------------
test();
//-----------------------------------------------------------------------------

function test()
{
  enterFunc ('test');
  printBugNumber(BUGNUMBER);
  printStatus (summary);
 
  try
  {
    eval('({a}) = b; with({}) { for(let y in z) { } }');
  }
  catch(ex)
  {
  }

  reportCompare(expect, actual, summary);

  exitFunc ('test');
}
