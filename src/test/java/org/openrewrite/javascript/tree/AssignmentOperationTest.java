/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.javascript.tree;

import org.junit.jupiter.api.Test;

@SuppressWarnings({"RedundantConditionalExpressionJS", "EqualityComparisonWithCoercionJS"})
public class AssignmentOperationTest extends ParserTest {

    @Test
    void minusEqual() {
        rewriteRun(
          javascript(
            """
              var n = 0
              n -= 5
              """
          )
        );
    }

    @Test
    void plusEqual() {
        rewriteRun(
          javascript(
            """
              var n = 0
              n += 5
              """
          )
        );
    }

    @Test
    void timesEqual() {
        rewriteRun(
          javascript(
            """
              var n = 0
              n *= 5
              """
          )
        );
    }

    @Test
    void divideEqual() {
        rewriteRun(
          javascript(
            """
              var n = 0
              n /= 5
              """
          )
        );
    }

    @Test
    void moduloEqual() {
        rewriteRun(
          javascript(
            """
              var n = 0
              n = n %= 5
              """
          )
        );
    }

    @Test
    void ternary() {
        rewriteRun(
          javascript(
            """
              let n = 0
              let r = ( n == 0 ) ? true : false
              """
          )
        );
    }
}
