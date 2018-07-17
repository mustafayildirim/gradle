/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal

import spock.lang.Specification

class ResultTest extends Specification {

    def "returns values"() {
        when:
        def result = Result.value("foo")

        then:
        result.hasValue()
        result.value == "foo"

        when:
        result.error

        then:
        thrown(IllegalStateException)
    }

    def "returns errors"() {
        when:
        def result = Result.error("foo: {0}", "bar")

        then:
        !result.hasValue()
        result.error == "foo: bar"

        when:
        result.value

        then:
        thrown(IllegalStateException)
    }

    def "does not accept null as value"() {
        when:
        Result.value(null)

        then:
        thrown(NullPointerException)
    }

    def "does not accept null as error message"() {
        when:
        Result.error(null)

        then:
        thrown(NullPointerException)
    }

    def "does not accept null as error arguments"() {
        when:
        Result.error("msg", (Object[]) null)

        then:
        thrown(NullPointerException)
    }
}
