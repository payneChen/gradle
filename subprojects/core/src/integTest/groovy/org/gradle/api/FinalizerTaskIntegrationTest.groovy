/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.api

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.util.internal.ToBeImplemented
import spock.lang.Ignore
import spock.lang.Issue

import static org.gradle.integtests.fixtures.executer.TaskOrderSpecs.any
import static org.gradle.integtests.fixtures.executer.TaskOrderSpecs.exact

class FinalizerTaskIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        buildFile '''
            class BreakingTask extends DefaultTask {
                @TaskAction
                def run() {}
            }

            tasks.withType(BreakingTask).configureEach { t ->
                if (project.hasProperty("${t.name}.broken")) {
                    t.doFirst { throw new RuntimeException("broken")}
                }
            }
        '''
    }

    @Issue("https://github.com/gradle/gradle/issues/21542")
    def "finalizer can depend on a task that it finalizes"() {
        given:
        buildFile '''
            task finalizer(type: BreakingTask) {
              dependsOn "finalizerDep"
            }

            task finalizerDep(type: BreakingTask) {
              finalizedBy finalizer
            }

            // Has to be ordered after the other tasks to trigger the issue
            task thing(type: BreakingTask) {
              finalizedBy finalizer
            }
        '''

        expect:
        2.times {
            succeeds "thing"
            result.assertTasksExecutedInOrder ":thing", ":finalizerDep", ":finalizer"
        }
        2.times {
            fails "thing", "-Pthing.broken"
            result.assertTasksExecutedInOrder ":thing", ":finalizerDep", ":finalizer"
        }
        2.times {
            fails "thing", "-PfinalizerDep.broken"
            result.assertTasksExecutedInOrder ":thing", ":finalizerDep"
        }
        2.times {
            fails "thing", "-PfinalizerDep.broken", "--continue"
            result.assertTasksExecutedInOrder ":thing", ":finalizerDep"
        }
        2.times {
            fails "thing", "-Pfinalizer.broken"
            result.assertTasksExecutedInOrder ":thing", ":finalizerDep", ":finalizer"
        }
    }

    def "finalizer can indirectly depend on the entry point finalized by it"() {
        given:
        buildFile '''
            task finalizer(type: BreakingTask) {
                dependsOn 'finalizerDep'
            }
            task finalizerDep(type: BreakingTask) {
                dependsOn 'entryPoint'
                finalizedBy 'finalizer'
            }
            task entryPoint(type: BreakingTask) {
                dependsOn 'entryPointDep'
                finalizedBy 'finalizer'
            }
            task entryPointDep(type: BreakingTask) {
            }
        '''

        expect:
        2.times {
            succeeds 'entryPoint'
            result.assertTasksExecutedInOrder ':entryPointDep', ':entryPoint', ':finalizerDep', ':finalizer'
        }
        2.times {
            fails 'entryPoint', '-PentryPoint.broken'
            result.assertTasksExecutedInOrder ':entryPointDep', ':entryPoint'
        }
        2.times {
            fails 'entryPoint', '-PentryPoint.broken', '--continue'
            result.assertTasksExecutedInOrder ':entryPointDep', ':entryPoint'
        }
        2.times {
            fails 'entryPoint', '-PentryPointDep.broken'
            result.assertTasksExecutedInOrder ':entryPointDep'
        }
        2.times {
            fails 'entryPoint', '-PentryPointDep.broken', '--continue'
            result.assertTasksExecutedInOrder ':entryPointDep'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDep.broken'
            result.assertTasksExecutedInOrder ':entryPointDep', ':entryPoint', ':finalizerDep'
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/21000")
    def "finalizer task can depend on finalized task that is not an entry point task"() {
        given:
        buildFile '''
            task finalizer(type: BreakingTask) {
                dependsOn 'finalizerDep'
            }
            task finalizerDep(type: BreakingTask) {
                dependsOn 'finalizerDepDep'
                finalizedBy 'finalizer'
            }
            task finalizerDepDep(type: BreakingTask) {
            }
            task entryPoint(type: BreakingTask) {
                finalizedBy 'finalizer'
            }
        '''

        expect:
        2.times {
            succeeds 'entryPoint'
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep', ':finalizerDep', ':finalizer'
        }
        2.times {
            fails 'entryPoint', '-PentryPoint.broken'
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep', ':finalizerDep', ':finalizer'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDepDep.broken'
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDep.broken'
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep', ':finalizerDep'
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/21000")
    def "finalizer task can depend on finalized tasks where one is an entry point task and one is not"() {
        given:
        buildFile '''
            task finalizer(type: BreakingTask) {
                dependsOn 'finalizerDep'
                dependsOn 'entryPoint'
            }
            task finalizerDep(type: BreakingTask) {
                dependsOn 'finalizerDepDep'
                finalizedBy 'finalizer'
            }
            task finalizerDepDep(type: BreakingTask) {
            }
            task entryPoint(type: BreakingTask) {
                finalizedBy 'finalizer'
            }
        '''

        expect:
        2.times {
            succeeds 'entryPoint'
            result.assertTaskOrder ':entryPoint', ':finalizerDepDep', ':finalizerDep', ':finalizer'
        }
        2.times {
            fails 'entryPoint', '-PentryPoint.broken'
            // TODO - shouldn't run the finalizer dependencies, as the finalizer will never run
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep', ':finalizerDep'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDepDep.broken'
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDep.broken'
            result.assertTasksExecutedInOrder ':entryPoint', ':finalizerDepDep', ':finalizerDep'
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/21000")
    def "finalizer task can depend on multiple finalized tasks"() {
        given:
        buildFile '''
            task finalizer(type: BreakingTask) {
                dependsOn 'finalized1', 'finalized2'
            }
            task finalized1(type: BreakingTask) {
                finalizedBy 'finalizer'
            }
            task finalized2(type: BreakingTask) {
                finalizedBy 'finalizer'
            }
            task entryPoint(type: BreakingTask) {
                finalizedBy 'finalizer'
            }
        '''

        expect:
        2.times {
            succeeds 'entryPoint'
            result.assertTaskOrder ':entryPoint', any(':finalized1', ':finalized2'), ':finalizer'
        }
        2.times {
            fails 'entryPoint', '-PentryPoint.broken'
            result.assertTaskOrder ':entryPoint', any(':finalized1', ':finalized2'), ':finalizer'
        }
        2.times {
            fails 'entryPoint', '-Pfinalized1.broken', '--continue' // add --continue so that finalized2 always runs
            result.assertTaskOrder ':entryPoint', any(':finalized1', ':finalized2')
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/21125")
    def "task can be finalized by and dependency of multiple finalizers"() {
        given:
        buildFile '''
            task finalizer1(type: BreakingTask) {
                dependsOn 'finalizerDep1'
                mustRunAfter 'finalizer2'
            }
            task finalizer2(type: BreakingTask) {
                dependsOn 'finalizerDep1'
            }
            task finalizerDep1(type: BreakingTask) {
                dependsOn 'finalizerDep2'
                finalizedBy 'finalizer1'
            }
            task finalizerDep2(type: BreakingTask) {
                dependsOn 'finalizerDep3'
                finalizedBy 'finalizer2'
                finalizedBy 'finalizer1'
            }
            task finalizerDep3(type: BreakingTask) {
            }
            task entryPoint(type: BreakingTask) {
                finalizedBy 'finalizer1'
                finalizedBy 'finalizer2'
            }
        '''

        expect:
        2.times {
            succeeds 'entryPoint'
            result.assertTaskOrder ':entryPoint', ':finalizerDep3', ':finalizerDep2', ':finalizerDep1', any(':finalizer2', ':finalizer1')
        }
        2.times {
            fails 'entryPoint', '-PentryPoint.broken'
            result.assertTaskOrder ':entryPoint', ':finalizerDep3', ':finalizerDep2', ':finalizerDep1', any(':finalizer2', ':finalizer1')
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDep3.broken'
            result.assertTaskOrder ':entryPoint', ':finalizerDep3'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDep2.broken'
            result.assertTaskOrder ':entryPoint', ':finalizerDep3', ':finalizerDep2'
        }
        2.times {
            fails 'entryPoint', '-PfinalizerDep1.broken'
            result.assertTaskOrder ':entryPoint', ':finalizerDep3', ':finalizerDep2', ':finalizerDep1'
        }
        2.times {
            fails 'entryPoint', '-Pfinalizer1.broken'
            result.assertTaskOrder ':entryPoint', ':finalizerDep3', ':finalizerDep2', ':finalizerDep1', any(':finalizer2', ':finalizer1')
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/21325")
    def "finalizer can depend on finalized entry point task and have other dependencies that are finalized by tasks that finalize their own dependencies"() {
        given:
        buildFile '''
            task assemble(type: BreakingTask) {
                dependsOn "classes"
            }
            task generatePermissions(type: BreakingTask) {
                dependsOn "classes"
            }
            task classes(type: BreakingTask) {
                finalizedBy "assemble", "generatePermissions"
                dependsOn "compileJava", "processResources"
            }
            task compileJava(type: BreakingTask) {
            }
            task processResources(type: BreakingTask) {
                finalizedBy "assemble"
            }
        '''

        expect:
        2.times {
            succeeds 'processResources'
            result.assertTaskOrder ':processResources', ':compileJava', ':classes', any(':generatePermissions', ':assemble')
        }
        2.times {
            fails 'processResources', '-PprocessResources.broken', '--continue' // add --continue to force compileJava to always run
            result.assertTasksExecutedInOrder ':processResources', ':compileJava'
        }
        2.times {
            fails 'processResources', '-Pclasses.broken'
            result.assertTasksExecutedInOrder ':processResources', ':compileJava', ':classes'
        }
    }

    void 'finalizer tasks are scheduled as expected (#requestedTasks)'() {
        given:
        setupProject()

        expect:
        2.times {
            succeeds(*requestedTasks)
            result.assertTasksExecutedInOrder any(':d', exact(':c', ':a')), ':b'
        }

        where:
        requestedTasks << [['a'], ['a', 'b'], ['d', 'a']]
    }

    void 'finalizer tasks work with task excluding (#excludedTask)'() {
        setupProject()
        executer.beforeExecute {
            withArguments('-x', excludedTask)
        }

        tasksNotInGraph.each { task ->
            buildFile << """
                gradle.taskGraph.whenReady { graph ->
                    assert !graph.hasTask('$task')
                }
            """
        }

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecutedInOrder(expectedExecutedTasks as Object[])
        }

        where:
        excludedTask | expectedExecutedTasks
        'b'          | [':c', ':a']
        'd'          | [':c', ':a', ':b']
        'a'          | []


        tasksNotInGraph = [':a', ':b', ':c', ':d'] - expectedExecutedTasks
    }

    void 'finalizer tasks work with --continue (#requestedTasks, #failingTask)'() {
        setupProject()
        executer.beforeExecute {
            withArguments('--continue')
        }

        buildFile << """
            ${failingTask}.doLast { throw new RuntimeException() }
        """

        expect:
        2.times {
            fails(*requestedTasks)
            result.assertTasksExecutedInOrder(expectedExecutedTasks as Object[])
        }

        where:
        requestedTasks | failingTask | expectedExecutedTasks
        ['a']          | 'c'         | [':c']
        ['a', 'b']     | 'a'         | [any(':d', exact(':c', ':a')), ':b']
        ['a', 'b']     | 'c'         | [any(':c', ':d'), ':b'] // :c and :d might run in parallel with the configuration cache
    }

    void 'finalizer tasks are not run when finalized task does not run due to unrelated task failure and not using --continue'() {
        given:
        buildScript("""
            task a {
            }
            task b {
                finalizedBy a
                doLast {
                    throw new RuntimeException("broken")
                }
            }
            task c {
            }
            task d {
                finalizedBy c
                mustRunAfter(b)
            }
        """)

        expect:
        2.times {
            fails("b", "d")
            result.assertTasksExecutedInOrder ":b", ":a"
        }
    }

    @Ignore
    void 'finalizer tasks work with task disabling (#taskDisablingStatement)'() {
        setupProject()
        buildFile << """
            $taskDisablingStatement

            gradle.taskGraph.whenReady { graph ->
                assert [a, b, c, d].every { graph.hasTask(it) }
            }
        """

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecuted(':c')
        }

        where:
        taskDisablingStatement << ['a.enabled = false', 'a.onlyIf {false}']
    }

    @ToBeImplemented
    void 'requesting to run finalizer task before finalized results in a circular dependency failure'() {
        setupProject()

        expect:
        2.times {
            // TODO - should fail
            succeeds 'b', 'a'
        }
    }

    void 'finalizer tasks are executed as expected in parallel builds'() {
        setupMultipleProjects()
        executer.beforeExecute {
            withArguments('--parallel')
        }

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecutedInOrder(any(':b:d', exact(':a:c', ':a:a')), ':b:b')
        }
    }

    void 'finalizers for finalizers are executed when finalized task is executed'() {
        buildFile """
            task a {
                finalizedBy 'b'
            }
            task b {
                finalizedBy 'c'
            }
            task c
        """

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/21346")
    void 'finalizer can be finalized'() {
        buildFile """
            task a(type: BreakingTask) {
                finalizedBy 'b'
            }
            task b(type: BreakingTask) {
                finalizedBy 'c'
            }
            task c(type: BreakingTask)
        """

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
        2.times {
            fails 'a', '-Pa.broken'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
        2.times {
            fails 'a', '-Pb.broken'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
        2.times {
            fails 'a', '-Pc.broken'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
    }

    void 'finalizers for finalizers can have a common dependency'() {
        buildFile """
            task a(type: BreakingTask) {
                finalizedBy 'b'
            }
            task b(type: BreakingTask) {
                dependsOn 'd'
                finalizedBy 'c'
            }
            task c(type: BreakingTask) {
                dependsOn 'd'
            }
            task d(type: BreakingTask)
        """

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecutedInOrder ':a', ':d', ':b', ':c'
        }
        2.times {
            fails 'a', '-Pa.broken'
            result.assertTasksExecutedInOrder ':a', ':d', ':b', ':c'
        }
        2.times {
            fails 'a', '-Pd.broken'
            result.assertTasksExecutedInOrder ':a', ':d'
        }
        2.times {
            fails 'a', '-Pb.broken'
            result.assertTasksExecutedInOrder ':a', ':d', ':b', ':c'
        }
    }

    void 'finalizer task can be used by multiple tasks that depend on one another'() {
        buildFile """
            task a(type: BreakingTask) {
                finalizedBy 'c'
            }
            task b(type: BreakingTask) {
                dependsOn 'a'
                finalizedBy 'c'
            }
            task c(type: BreakingTask)
        """

        expect:
        2.times {
            succeeds 'b'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
        2.times {
            fails 'b', '-Pb.broken'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
        2.times {
            fails 'b', '-Pa.broken'
            result.assertTasksExecutedInOrder ':a', ':c'
        }
        2.times {
            fails 'b', '-Pc.broken'
            result.assertTasksExecutedInOrder ':a', ':b', ':c'
        }
    }

    void 'finalizer tasks are executed after their dependencies'() {
        buildFile """
            task a {
                dependsOn 'b', 'c'
            }
            task b
            task c {
                finalizedBy 'b'
            }
        """

        expect:
        2.times {
            succeeds 'a'
            result.assertTasksExecutedInOrder ':c', ':b', ':a'
        }
    }

    void 'circular dependency errors are detected for finalizer tasks'() {
        buildFile """
            task a {
                finalizedBy 'b'
                dependsOn 'c'
            }
            task b
            task c {
                mustRunAfter 'b'
            }
        """

        expect:
        2.times {
            fails 'a'
            failure.assertHasDescription """|Circular dependency between the following tasks:
                                            |:a
                                            |\\--- :c
                                            |     \\--- :b
                                            |          \\--- :a (*)
                                            |
                                            |(*) - details omitted (listed previously)""".stripMargin()
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/2293")
    void 'circular dependency is detected on cycle within chained finalizers'() {
        buildFile """
            task a {
                finalizedBy 'b'
            }
            task b {
                finalizedBy 'c'
            }
            task c {
                finalizedBy 'c'
            }
        """

        expect:
        2.times {
            fails 'a'
            failure.assertHasDescription """|Circular dependency between the following tasks:
                                            |:c
                                            |\\--- :c (*)
                                            |
                                            |(*) - details omitted (listed previously)""".stripMargin()
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/2293")
    def "circular dependency detected with complex finalizedBy cycle in the graph"() {
        buildFile """
            task a
            task b
            task c
            task d
            task e
            task f

            a.dependsOn b
            b.dependsOn c
            b.finalizedBy d
            d.dependsOn f
            e.dependsOn d
            f.dependsOn e
        """

        expect:
        2.times {
            fails 'a'
            failure.assertHasDescription """|Circular dependency between the following tasks:
                                            |:d
                                            |\\--- :f
                                            |     \\--- :e
                                            |          \\--- :d (*)
                                            |
                                            |(*) - details omitted (listed previously)""".stripMargin()
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/20800")
    void 'finalizedBy dependencies can run before finalized task to honour mustRunAfter constraints'() {
        given:
        buildFile '''
            task dockerTest {
                dependsOn 'dockerUp'     // dependsOn createContainer mustRunAfter removeContainer
                finalizedBy 'dockerStop' // dependsOn removeContainer
            }

            task dockerUp {
                dependsOn 'createContainer'
            }

            task dockerStop {
                dependsOn 'removeContainer'
            }

            task createContainer {
                mustRunAfter 'removeContainer'
            }

            task removeContainer {
            }
        '''

        expect:
        succeeds 'dockerTest'

        and:
        result.assertTasksExecutedInOrder ':removeContainer', ':createContainer', ':dockerUp', ':dockerTest', ':dockerStop'
    }

    @Issue("https://github.com/gradle/gradle/issues/5415")
    void 'finalizers are executed after the last task to be finalized'() {
        settingsFile << """
            include "a"
            include "b"
        """
        buildFile """
            configure(project(':a')) {
                task finalizer {
                    doLast {
                        sleep 100
                    }
                }

                task foo {
                    finalizedBy finalizer
                    doLast {
                        sleep 500
                    }
                }
            }

            configure(project(':b')) {
                task foo {
                    finalizedBy ':a:finalizer'
                    doLast {
                        sleep 1000
                    }
                }
            }
        """

        expect:
        2.times {
            run "foo", "--parallel"
            result.assertTaskOrder(any(":a:foo", ":b:foo"), ":a:finalizer")
        }
    }

    @ToBeImplemented("https://github.com/gradle/gradle/issues/10549")
    def "mustRunAfter is respected for finalizer without direct dependency"() {
        settingsFile << """
            include 'a'
            include 'b'
        """
        buildFile """
            configure(project(':a')) {
                task finalizer {
                    doLast {
                        println "finalized"
                    }
                }

                task work {
                    doLast {
                        sleep 1000
                        println "executed \${path}"
                    }
                    finalizedBy(finalizer)
                }
            }

            configure(project(':b')) {
                task work {
                    doLast {
                        println "executed \${path}"
                    }
                    mustRunAfter(":a:finalizer")
                }
            }
        """

        expect:
        2.times {
            run("work", "--parallel")
            // TODO: Should be:
            // result.assertTaskOrder(":a:work", ":a:finalizer", ":b:work")
            result.assertTaskOrder(any(exact(":a:work", ":a:finalizer"), ":b:work"))
        }

        and: "Apply workaround"
        buildFile """
            configure(project(':b')) {
                work.mustRunAfter(":a:work")
            }
        """
        2.times {
            run("work", "--parallel")
            result.assertTaskOrder(":a:work", ":a:finalizer", ":b:work")
        }
    }

    private void setupProject() {
        buildFile """
            class NotParallel extends DefaultTask {}

            task a {
                finalizedBy 'b'
                dependsOn 'c'
            }
            task b {
                dependsOn 'd'
            }
            task c(type: NotParallel)
            task d(type: NotParallel)
        """
    }

    private void setupMultipleProjects() {
        settingsFile << """
            include 'a', 'b'
        """

        file('a/build.gradle') << """
            task a {
                finalizedBy ':b:b'
                dependsOn 'c'
            }
            task c
        """

        file('b/build.gradle') << """
            task b {
                dependsOn 'd'
            }
            task d
        """
    }
}
