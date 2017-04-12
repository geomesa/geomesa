/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.utils.text

import java.util.regex.Pattern

import org.apache.commons.lang.StringEscapeUtils
import org.parboiled.scala._

/**
  * Base class for parboiled parsers that provides methods for string and number matching
  */
class BasicParser extends Parser {

  private val controlCharPattern = Pattern.compile("""\p{Cntrl}""")

  def int: Rule1[Int] = rule { (optional("-") ~ oneOrMore("0" - "9")) ~> (_.toInt) }

  def long: Rule1[Long] = rule { (optional("-") ~ oneOrMore("0" - "9")) ~> (_.toLong) }

  def char: Rule0 = rule { "a" - "z" | "A" - "Z" | "0" - "9" | "_" }

  def charWithParens: Rule0 = rule { "a" - "z" | "A" - "Z" | "0" - "9" | "_" | "(" | ")" }

  def string: Rule1[String] = rule { quotedString | singleQuotedString | unquotedString }

  def unquotedString: Rule1[String] = rule { oneOrMore(char) ~> { c => c } }

  def stringWithParens: Rule1[String] = rule { quotedString | singleQuotedString | unquotedString | unquotedStringWithParens }

  def unquotedStringWithParens: Rule1[String] = rule { oneOrMore(charWithParens) ~> { c => c } }


  def quotedString: Rule1[String] = rule {
    "\"" ~ zeroOrMore((noneOf("""\"""") ~? notControlChar) | escapedChar) ~> StringEscapeUtils.unescapeJava ~ "\""
  }

  def singleQuotedString: Rule1[String] = rule {
    "'" ~ zeroOrMore((noneOf("""\'""") ~? notControlChar) | escapedChar) ~> StringEscapeUtils.unescapeJava ~ "'"
  }

  private def escapedChar: Rule0 = rule {
    "\\" ~ (anyOf("""\/"'bfnrt""") | "u" ~ nTimes(4, "0" - "9" | "a" - "f" | "A" - "F"))
  }

  private def notControlChar(s: String): Boolean = !controlCharPattern.matcher(s).matches()
}
