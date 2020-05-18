/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.instantexecution

import org.gradle.api.Project
import org.gradle.api.internal.project.DefaultProject
import org.gradle.api.invocation.Gradle
import org.gradle.invocation.DefaultGradle
import spock.lang.Unroll


class InstantExecutionProblemReportingIntegrationTest extends AbstractInstantExecutionIntegrationTest {

    def "state serialization errors always halt the build and invalidate the cache"() {
        given:
        def instant = newInstantExecutionFixture()

        buildFile << """
            class BrokenSerializable implements java.io.Serializable {
                private void writeObject(java.io.ObjectOutputStream out) throws IOException {
                    throw new RuntimeException("BOOM")
                }
            }

            class BrokenTaskType extends DefaultTask {
                final prop = new BrokenSerializable()
            }

            task broken(type: BrokenTaskType)
        """

        when:
        instantFails 'broken'

        then:
        failure.assertTasksExecuted()

        and:
        instant.assertStateStoreFailed()
        failure.assertHasFailures(1)
        failure.assertHasFileName("Build file '${buildFile.absolutePath}'")
        failure.assertHasLineNumber(4)
        failure.assertHasDescription("Instant execution state could not be cached: field 'prop' from type 'BrokenTaskType': error writing value of type 'BrokenSerializable'")
        failure.assertHasCause("BOOM")
        problems.assertResultHasProblems(failure) {
        }

        when:
        problems.withDoNotFailOnProblems()
        instantFails 'broken'

        then:
        failure.assertTasksExecuted()

        and:
        instant.assertStateStoreFailed()
        failure.assertHasFailures(1)
        failure.assertHasFileName("Build file '${buildFile.absolutePath}'")
        failure.assertHasLineNumber(4)
        failure.assertHasDescription("Instant execution state could not be cached: field 'prop' from type 'BrokenTaskType': error writing value of type 'BrokenSerializable'")
        failure.assertHasCause("BOOM")
        problems.assertResultHasProblems(failure) {
        }
    }

    def "state serialization errors always halt the build and earlier problems reported"() {
        given:
        def instant = newInstantExecutionFixture()

        buildFile << """
            class BrokenSerializable implements java.io.Serializable {
                private void writeObject(java.io.ObjectOutputStream out) throws IOException {
                    throw new RuntimeException("BOOM")
                }
            }

            class BrokenTaskType extends DefaultTask {
                final prop = new BrokenSerializable()
            }

            task problems {
                inputs.property 'brokenProperty', project
                inputs.property 'otherBrokenProperty', project
            }

            task broken(type: BrokenTaskType)
        """

        when:
        instantFails 'problems', 'broken'

        then:
        failure.assertTasksExecuted()

        and:
        instant.assertStateStoreFailed()
        failure.assertHasFailures(1)
        failure.assertHasFileName("Build file '${buildFile.absolutePath}'")
        failure.assertHasLineNumber(4)
        failure.assertHasDescription("Instant execution state could not be cached: field 'prop' from type 'BrokenTaskType': error writing value of type 'BrokenSerializable'")
        failure.assertHasCause("BOOM")
        problems.assertResultHasProblems(failure) {
            withProblem("input property 'brokenProperty' of ':problems': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("input property 'otherBrokenProperty' of ':problems': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
        }

        when:
        problems.withDoNotFailOnProblems()
        instantFails 'problems', 'broken'

        then:
        failure.assertTasksExecuted()

        and:
        instant.assertStateStoreFailed()
        failure.assertHasFailures(1)
        failure.assertHasFileName("Build file '${buildFile.absolutePath}'")
        failure.assertHasLineNumber(4)
        failure.assertHasDescription("Instant execution state could not be cached: field 'prop' from type 'BrokenTaskType': error writing value of type 'BrokenSerializable'")
        failure.assertHasCause("BOOM")
        problems.assertResultHasProblems(failure) {
            withProblem("input property 'brokenProperty' of ':problems': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("input property 'otherBrokenProperty' of ':problems': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
        }
    }

    def "serialization problems are reported and fail the build by default and do not invalidate cache"() {
        given:
        def instantExecution = newInstantExecutionFixture()

        buildFile << """
            class BrokenTaskType extends DefaultTask {
                final prop = project
                final anotherProp = project.configurations
            }

            task problems {
                inputs.property 'brokenProperty', project
                inputs.property 'otherBrokenProperty', project
            }

            task moreProblems(type: BrokenTaskType)

            task ok

            task all {
                dependsOn 'problems', 'moreProblems', 'ok'
            }
        """

        when:
        instantFails 'all'

        then:
        executed(':problems', ':moreProblems', ':ok', ':all')
        instantExecution.assertStateStored() // does not fail
        problems.assertFailureHasProblems(failure) {
            withProblem("field 'anotherProp' from type 'BrokenTaskType': cannot serialize object of type 'org.gradle.api.internal.artifacts.configurations.DefaultConfigurationContainer', a subtype of 'org.gradle.api.artifacts.ConfigurationContainer', as these are not supported with instant execution.")
            withProblem("field 'prop' from type 'BrokenTaskType': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("input property 'brokenProperty' of ':problems': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("input property 'otherBrokenProperty' of ':problems': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
        }
        failure.assertHasFailures(1)

        when:
        problems.withDoNotFailOnProblems()
        instantRun 'all'

        then:
        executed(':problems', ':moreProblems', ':ok', ':all')
        instantExecution.assertStateLoaded()
        problems.assertResultHasProblems(result) {
            withProblem("field 'anotherProp' from type 'BrokenTaskType': cannot deserialize object of type 'org.gradle.api.artifacts.ConfigurationContainer' as these are not supported with instant execution.")
            withProblem("field 'prop' from type 'BrokenTaskType': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("input property 'brokenProperty' of ':problems': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("input property 'otherBrokenProperty' of ':problems': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
        }

        when:
        instantFails 'all'

        then:
        executed(':problems', ':moreProblems', ':ok', ':all')
        instantExecution.assertStateLoaded()
        problems.assertFailureHasProblems(failure) {
            withProblem("field 'anotherProp' from type 'BrokenTaskType': cannot deserialize object of type 'org.gradle.api.artifacts.ConfigurationContainer' as these are not supported with instant execution.")
            withProblem("field 'prop' from type 'BrokenTaskType': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("input property 'brokenProperty' of ':problems': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("input property 'otherBrokenProperty' of ':problems': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
        }
    }

    def "configuration time problems are reported and fail the build by default only when configuration is executed and do not invalidate the cache"() {
        given:
        def instantExecution = newInstantExecutionFixture()

        buildFile << """
            gradle.buildFinished { }
            gradle.buildFinished { }

            task all
        """

        when:
        instantFails 'all'

        then:
        executed(':all')
        instantExecution.assertStateStored() // does not fail
        problems.assertFailureHasProblems(failure) {
            withProblem("unknown location: registration of listener on 'Gradle.buildFinished' is unsupported")
            withTotalProblemsCount(2)
        }
        failure.assertHasFailures(1)

        when:
        problems.withDoNotFailOnProblems()
        instantRun 'all'

        then:
        executed(':all')
        instantExecution.assertStateLoaded()
        problems.assertResultHasProblems(result) {
            // TODO - should give some indication to the user that the build may not work correctly
        }

        when:
        instantRun 'all'

        then:
        executed(':all')
        instantExecution.assertStateLoaded()
        problems.assertResultHasProblems(result) {
            // TODO - should fail and give some indication to the user why
        }
    }

    def "task execution problems are reported and fail the build by default and do not invalidate cache"() {
        given:
        def instantExecution = newInstantExecutionFixture()

        buildFile << """
            task broken {
                doLast {
                    println("project = " + project.name)
                }
            }
            task anotherBroken {
                doLast {
                    println("configurations = " + project.configurations.all)
                }
            }

            task all {
                dependsOn 'broken', 'anotherBroken'
            }
        """

        when:
        instantFails 'all'

        then:
        executed(':all')
        instantExecution.assertStateStored() // does not fail
        problems.assertFailureHasProblems(failure) {
            withProblem("task `:anotherBroken` of type `org.gradle.api.DefaultTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("task `:broken` of type `org.gradle.api.DefaultTask`: invocation of 'Task.project' at execution time is unsupported.")
        }
        failure.assertHasFailures(1)

        when:
        problems.withDoNotFailOnProblems()
        instantRun 'all'

        then:
        executed(':all')
        instantExecution.assertStateLoaded()
        problems.assertResultHasProblems(result) {
            withProblem("task `:anotherBroken` of type `org.gradle.api.DefaultTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("task `:broken` of type `org.gradle.api.DefaultTask`: invocation of 'Task.project' at execution time is unsupported.")
        }

        when:
        instantFails 'all'

        then:
        executed(':all')
        instantExecution.assertStateLoaded()
        problems.assertFailureHasProblems(failure) {
            withProblem("task `:anotherBroken` of type `org.gradle.api.DefaultTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("task `:broken` of type `org.gradle.api.DefaultTask`: invocation of 'Task.project' at execution time is unsupported.")
        }
    }

    def "problems are reported and fail the build when failOnProblems is false but maxProblems is reached"() {
        given:
        def instantExecution = newInstantExecutionFixture()

        buildFile << """
            class BrokenTask extends DefaultTask {
                @Internal
                final broken = project

                @TaskAction
                def go() {
                    try {
                        println("project = " + project)
                    } catch(Exception e) {
                        // should not happen, problems are collected
                        throw new RuntimeException("broken")
                    }
                }
            }

            task problems(type: BrokenTask)
            task moreProblems(type: BrokenTask)

            task all {
                dependsOn('problems', 'moreProblems')
            }
        """

        when:
        instantFails 'all', "-D${SystemProperties.failOnProblems}=false", "-D${SystemProperties.maxProblems}=2"

        then:
        executed(':problems', ':moreProblems', ':all')
        instantExecution.assertStateStored() // does not fail
        problems.assertFailureHasTooManyProblems(failure) {
            withProblem("task `:moreProblems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("field 'broken' from type 'BrokenTask': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("task `:problems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            totalProblemsCount = 4
        }

        when:
        instantFails 'all', "-D${SystemProperties.failOnProblems}=false", "-D${SystemProperties.maxProblems}=3"

        then:
        executed(':problems', ':moreProblems', ':all')
        instantExecution.assertStateLoaded()
        problems.assertFailureHasTooManyProblems(failure) {
            withProblem("task `:moreProblems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("field 'broken' from type 'BrokenTask': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("task `:problems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            totalProblemsCount = 4
        }
        failure.assertHasFailures(1)

        when:
        instantRun 'all', "-D${SystemProperties.failOnProblems}=false", "-D${SystemProperties.maxProblems}=4"

        then:
        executed(':problems', ':moreProblems', ':all')
        instantExecution.assertStateLoaded()
        problems.assertResultHasProblems(result) {
            withProblem("task `:moreProblems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("field 'broken' from type 'BrokenTask': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("task `:problems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            totalProblemsCount = 4
        }
    }

    def "fails regardless of maxProblems when failOnProblems is true"() {
        given:
        def instantExecution = newInstantExecutionFixture()

        buildFile << """
            class BrokenTask extends DefaultTask {
                @Internal
                final broken = project

                @TaskAction
                def go() {
                    println("project = " + project)
                }
            }

            task problems(type: BrokenTask)
            task moreProblems(type: BrokenTask)

            task all {
                dependsOn('problems', 'moreProblems')
            }
        """

        when:
        instantFails 'all', "-D${SystemProperties.maxProblems}=2"

        then:
        executed(':problems', ':moreProblems', ':all')
        instantExecution.assertStateStored() // does not fail
        problems.assertFailureHasProblems(failure) {
            withProblem("task `:moreProblems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("field 'broken' from type 'BrokenTask': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("task `:problems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            totalProblemsCount = 4
        }

        when:
        instantFails 'all', "-D${SystemProperties.maxProblems}=2"

        then:
        executed(':problems', ':moreProblems', ':all')
        instantExecution.assertStateLoaded()
        problems.assertFailureHasProblems(failure) {
            withProblem("task `:moreProblems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("field 'broken' from type 'BrokenTask': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("task `:problems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            totalProblemsCount = 4
        }
        failure.assertHasFailures(1)

        when:
        instantRun 'all', "-D${SystemProperties.maxProblems}=2000"

        then:
        executed(':problems', ':moreProblems', ':all')
        instantExecution.assertStateLoaded()
        problems.assertResultHasProblems(result) {
            withProblem("task `:moreProblems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            withProblem("field 'broken' from type 'BrokenTask': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
            withProblem("task `:problems` of type `BrokenTask`: invocation of 'Task.project' at execution time is unsupported.")
            totalProblemsCount = 4
        }
    }

    def "report does not include configuration and runtime problems from buildSrc"() {
        file("buildSrc/build.gradle") << """
            // These should not be reported, as neither of these are serialized
            gradle.buildFinished { }
            classes.doLast { t -> t.project }
        """
        file("build.gradle") << """
            gradle.addListener(new BuildAdapter())
            task broken {
                inputs.property('p', project)
            }
        """

        when:
        instantFails("broken")

        then:
        problems.assertFailureHasProblems(failure) {
            withProblem("input property 'p' of ':broken': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution.")
            withProblem("unknown location: registration of listener on 'Gradle.addListener' is unsupported")
        }
        failure.assertHasFailures(1)

        when:
        problems.withDoNotFailOnProblems()
        instantRun("broken")

        then:
        problems.assertResultHasProblems(result) {
            withProblem("input property 'p' of ':broken': cannot deserialize object of type 'org.gradle.api.Project' as these are not supported with instant execution.")
        }
    }

    private Map<String, List<String>> withStateSerializationProblems() {
        buildFile << """
            task taskWithStateSerializationProblems {
                inputs.property 'brokenProperty', project
                inputs.property 'otherBrokenProperty', project
            }
        """
        return [
            store: [
                "input property 'brokenProperty' of ':taskWithStateSerializationProblems': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution.",
                "input property 'otherBrokenProperty' of ':taskWithStateSerializationProblems': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution.",
            ],
            load: [
                "input property 'brokenProperty' of ':taskWithStateSerializationProblems': cannot deserialize object of type '${Project.name}' as these are not supported with instant execution.",
                "input property 'otherBrokenProperty' of ':taskWithStateSerializationProblems': cannot deserialize object of type '${Project.name}' as these are not supported with instant execution."
            ]
        ]
    }

    private List<String> withTaskExecutionProblems() {
        buildFile << """
            abstract class MyTask extends DefaultTask {
                @TaskAction
                def action() {
                    println("project:${'\$'}{project.name}")
                }
            }

            tasks.register("a", MyTask)
            tasks.register("b", MyTask)
        """
        return [
            "task `:a` of type `MyTask`: invocation of 'Task.project' at execution time is unsupported.",
            "task `:b` of type `MyTask`: invocation of 'Task.project' at execution time is unsupported."
        ]
    }

    @Unroll
    def "reports #invocation access during execution"() {

        def instantExecution = newInstantExecutionFixture()

        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            abstract class MyTask extends DefaultTask {
                @TaskAction
                def action() {
                    println($code)
                }
            }

            tasks.register("a", MyTask)
            tasks.register("b", MyTask)
        """

        and:
        def expectedProblems = [
            "task `:a` of type `MyTask`: invocation of '$invocation' at execution time is unsupported.",
            "task `:b` of type `MyTask`: invocation of '$invocation' at execution time is unsupported."
        ]

        when:
        instantFails "a", "b"

        then:
        instantExecution.assertStateStored()
        problems.assertFailureHasProblems(failure) {
            withUniqueProblems(expectedProblems)
            withProblemsWithStackTraceCount(2)
        }

        when:
        problems.withDoNotFailOnProblems()
        instantRun "a", "b"

        then:
        instantExecution.assertStateLoaded()

        and:
        problems.assertResultHasProblems(result) {
            withUniqueProblems(expectedProblems)
            withProblemsWithStackTraceCount(2)
        }

        where:
        invocation              | code
        'Task.project'          | 'project.name'
        'Task.dependsOn'        | 'dependsOn'
        'Task.taskDependencies' | 'taskDependencies'
    }

    @Unroll
    def "report build listener registration on #registrationPoint"() {

        given:
        buildFile << code

        when:
        executer.noDeprecationChecks()
        instantFails 'help'

        then:
        problems.assertFailureHasProblems(failure) {
            withUniqueProblems("unknown location: registration of listener on '$registrationPoint' is unsupported")
            withProblemsWithStackTraceCount(1)
        }

        where:
        registrationPoint         | code
        "Gradle.addBuildListener" | "gradle.addBuildListener(new BuildAdapter())"
        "Gradle.addListener"      | "gradle.addListener(new BuildAdapter())"
        "Gradle.buildStarted"     | "gradle.buildStarted {}"
        "Gradle.buildFinished"    | "gradle.buildFinished {}"
    }

    @Unroll
    def "does not report problems on configuration listener registration on #registrationPoint"() {

        given:
        buildFile << """

            class ProjectEvaluationAdapter implements ProjectEvaluationListener {
                void beforeEvaluate(Project project) {}
                void afterEvaluate(Project project, ProjectState state) {}
            }

            $code
        """

        expect:
        instantRun 'help'

        where:
        registrationPoint                     | code
        "Gradle.addProjectEvaluationListener" | "gradle.addProjectEvaluationListener(new ProjectEvaluationAdapter())"
        "Gradle.addListener"                  | "gradle.addListener(new ProjectEvaluationAdapter())"
        "Gradle.beforeSettings"               | "gradle.beforeSettings {}"
        "Gradle.settingsEvaluated"            | "gradle.settingsEvaluated {}"
        "Gradle.projectsLoaded"               | "gradle.projectsLoaded {}"
        "Gradle.beforeProject"                | "gradle.beforeProject {}"
        "Gradle.afterProject"                 | "gradle.afterProject {}"
        "Gradle.projectsEvaluated"            | "gradle.projectsEvaluated {}"
    }

    def "summarizes unsupported properties"() {
        given:
        buildFile << """
            class SomeBean {
                Gradle gradle
                def nested = new NestedBean()
            }

            class NestedBean {
                Gradle gradle
                Project project
            }

            class SomeTask extends DefaultTask {
                private final bean = new SomeBean()

                SomeTask() {
                    bean.gradle = project.gradle
                    bean.nested.gradle = project.gradle
                    bean.nested.project = project
                }

                @TaskAction
                void run() {
                }
            }

            // ensure there are multiple warnings for the same properties
            task a(type: SomeTask)
            task b(type: SomeTask)
            task c(dependsOn: [a, b])
        """

        when:
        problems.withDoNotFailOnProblems()
        instantRun "c"

        then:
        problems.assertResultHasProblems(result) {
            withTotalProblemsCount(6)
            withUniqueProblems(
                "field 'gradle' from type 'SomeBean': cannot serialize object of type '${DefaultGradle.name}', a subtype of '${Gradle.name}', as these are not supported with instant execution.",
                "field 'gradle' from type 'NestedBean': cannot serialize object of type '${DefaultGradle.name}', a subtype of '${Gradle.name}', as these are not supported with instant execution.",
                "field 'project' from type 'NestedBean': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution."
            )
            withProblemsWithStackTraceCount(0)
        }
    }

    @Unroll
    def "can limit the number of problems to #maxProblems"() {
        given:
        buildFile << """
            class Bean {
                Project p1
                Project p2
                Project p3
            }

            class FooTask extends DefaultTask {
                private final bean = new Bean()

                FooTask() {
                    bean.with {
                        p1 = project
                        p2 = project
                        p3 = project
                    }
                }

                @TaskAction
                void run() {
                }
            }

            task foo(type: FooTask)
        """

        when:
        problems.withDoNotFailOnProblems()
        instantFails "foo", "-D${SystemProperties.maxProblems}=$maxProblems"

        then:
        problems.assertFailureHasTooManyProblems(failure) {
            withTotalProblemsCount(3)
            withProblem("field 'p1' from type 'Bean': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution.")
            withProblem("field 'p2' from type 'Bean': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution.")
            withProblem("field 'p3' from type 'Bean': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution.")
        }

        where:
        maxProblems << [0, 1, 2]
        expectedNumberOfProblems = Math.max(1, maxProblems)
    }

    def "can request to not fail on problems"() {
        given:
        buildFile << """
            class Bean { Project p1 }

            class FooTask extends DefaultTask {
                private final bean = new Bean()
                FooTask() { bean.p1 = project }
                @TaskAction void run() {}
            }

            task foo(type: FooTask)
        """

        when:
        instantRun "foo", "-D${SystemProperties.failOnProblems}=false"

        then:
        problems.assertResultHasProblems(result) {
            withUniqueProblems(
                "field 'p1' from type 'Bean': cannot serialize object of type '${DefaultProject.name}', a subtype of '${Project.name}', as these are not supported with instant execution."
            )
            withProblemsWithStackTraceCount(0)
        }
    }
}
