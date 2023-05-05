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
import org.junitpioneer.jupiter.ExpectedToFail;

@SuppressWarnings({"JSUnusedLocalSymbols", "JSUnresolvedVariable"})
public class MethodDeclarationTest extends ParserTest {

    @Test
    void functionDeclaration() {
        rewriteRun(
          javascript(
            """
              function foo ( ) { }
              """
          )
        );
    }

    @Test
    void functionParameters() {
        rewriteRun(
          javascript(
            """
              function foo ( x : number , y : number ) { }
              """
          )
        );
    }

    @Test
    void decorator() {
        rewriteRun(
          javascript(
            """
              function enumerable ( value : boolean ) {
                  return function ( target : any ,
                          propertyKey : string ,
                          descriptor : PropertyDescriptor ) {
                      descriptor . enumerable = value ;
                  };
              }
              """
          )
        );
    }

    @Test
    void methodDeclaration() {
        rewriteRun(
          javascript(
            """
              class Foo {
                  foo ( ) {
                  }
              }
              """
          )
        );
    }

    @ExpectedToFail
    @Test
    void arrowDeclaration() {
        rewriteRun(
          javascript(
            """
              let sum = ( a : number , b : number ) : number => {
                  return a + b ;
              }
              """
          )
        );
    }
}
