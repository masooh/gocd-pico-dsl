/**
 * Copyright 1&1 Internet AG, https://github.com/1and1/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oneandone.gocd.picodsl.dsl

import org.jgrapht.Graph
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import java.util.*

@DslMarker
annotation class PipelinePicoDslMarker

// todo ContextMarker an alle Element, wo Sichtbarkeit fehlt Methode wiederholen und super aufrufen

@PipelinePicoDslMarker
sealed class Context(val pipelineGroup: PipelineGroup) {
    val data = mutableMapOf<String, String>()
    val postProcessors: MutableList<(PipelineSingle) -> Unit> = mutableListOf()

    fun forAll(enhancePipeline: PipelineSingle.() -> Unit) {
        postProcessors.add(enhancePipeline)
    }

    fun pipeline(name: String, block: PipelineSingle.() -> Unit): PipelineSingle {
        return pipelineGroup.pipeline(name, block)
    }
}

class ParallelContext(val pipelineParallel: PipelineParallel) : Context(pipelineParallel) {
    fun sequence(init: PipelineSequence.() -> Unit): PipelineSequence {
        return pipelineParallel.sequence(init)
    }
}

class SequenceContext(val pipelineSequence: PipelineSequence) : Context(pipelineSequence) {
    fun parallel(init: PipelineParallel.() -> Unit): PipelineParallel {
        return pipelineSequence.parallel(init)
    }
}

object ContextStack {
    val context: Deque<Context> = LinkedList()
    val current: Context?
        get() = context.peekLast()
}

abstract class PipelineContainer {
    val pipelinesInContainer = mutableListOf<PipelineContainer>()
    val graphProcessors = mutableListOf<PipelineSingle.(Graph<PipelineSingle, DefaultEdge>) -> Unit>()

    abstract fun getEndingPipelines(): List<PipelineSingle>
    abstract fun getStartingPipelines(): List<PipelineSingle>

    open fun getAllPipelines(): List<PipelineSingle> = pipelinesInContainer.flatMap { it.getAllPipelines() }
    abstract fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>)

    open fun runGraphProcessors(graph: Graph<PipelineSingle, DefaultEdge>) {
        pipelinesInContainer.forEach { it.runGraphProcessors(graph) }
    }
}

@PipelinePicoDslMarker
sealed class PipelineGroup : PipelineContainer() {

    /** Creates pipeline and adds it to container */
    fun pipeline(name: String, block: PipelineSingle.() -> Unit): PipelineSingle {
        val pipelineSingle = PipelineSingle(name)
        pipelineSingle.block()

        pipelinesInContainer.add(pipelineSingle)

        return pipelineSingle
    }

    fun context(init: Context.() -> Unit = {}, block: Context.() -> Unit): Context {
        val context = createContext()
        ContextStack.context.add(context)

        context.init()
        context.block()

        getAllPipelines().forEach { pipeline ->
            context.postProcessors.forEach { processor ->
                pipeline.apply(processor)
            }
        }
        ContextStack.context.removeLast()
        return context
    }

    abstract fun createContext(): Context
}

@PipelinePicoDslMarker
class GocdConfig {
    val graph: Graph<PipelineSingle, DefaultEdge> = SimpleDirectedGraph(DefaultEdge::class.java)

    val pipelines: MutableList<PipelineGroup> = mutableListOf()

    fun environments() {}

    fun sequence(init: PipelineSequence.() -> Unit): PipelineSequence {
        val pipelineSequence = PipelineSequence()
        pipelineSequence.init()
        pipelineSequence.addPipelineGroupToGraph(graph)

        pipelines.add(pipelineSequence)
        return pipelineSequence
    }

    fun parallel(init: PipelineParallel.() -> Unit): PipelineParallel {
        val pipelineParallel = PipelineParallel(null)
        pipelineParallel.init()
        pipelineParallel.addPipelineGroupToGraph(graph)

        pipelines.add(pipelineParallel)
        return pipelineParallel
    }

    fun finalize(): GocdConfig {
        pipelines.forEach {
            it.runGraphProcessors(graph)
        }
        validate()
        return this
    }

    private fun validate() {
        val pipeLineWithoutStageOrTemplate = graph.vertexSet().find { pipeline ->
            pipeline.template == null && pipeline.stages.size == 0
        }

        pipeLineWithoutStageOrTemplate?.let {
            throw IllegalArgumentException("pipeline ${it.name} has neither template nor stage")
        }
    }
}

fun gocd(init: GocdConfig.() -> Unit) = GocdConfig().apply(init).finalize()

class PipelineSequence : PipelineGroup() {
    override fun createContext() = SequenceContext(this)

    override fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>) {
        pipelinesInContainer.forEach { it.addPipelineGroupToGraph(graph) }

        var fromGroup = pipelinesInContainer.first()

        pipelinesInContainer.drop(1).forEach { toGroup ->
            fromGroup.getEndingPipelines().forEach { from ->
                toGroup.getStartingPipelines().forEach { to ->
                    graph.addEdge(from, to)
                }
            }
            fromGroup = toGroup
        }
    }

    override fun getStartingPipelines(): List<PipelineSingle> = pipelinesInContainer.first().getStartingPipelines()
    override fun getEndingPipelines(): List<PipelineSingle> = pipelinesInContainer.last().getEndingPipelines()

    fun parallel(init: PipelineParallel.() -> Unit): PipelineParallel {
        val lastPipeline = if (pipelinesInContainer.isNotEmpty()) pipelinesInContainer.last() as PipelineSingle else null
        val pipelineParallel = PipelineParallel(lastPipeline)
        pipelineParallel.init()

        pipelinesInContainer.add(pipelineParallel)

        return pipelineParallel
    }

    fun group(groupName: String, block: SequenceContext.() -> Unit): SequenceContext {
        return context({
            postProcessors.add { pipeline ->
                if (pipeline.group == null) {
                    pipeline.group = groupName
                }
            }
        }, block as Context.() -> Unit) as SequenceContext
    }
}

class PipelineParallel(private val forkPipeline: PipelineSingle?) : PipelineGroup() {
    override fun createContext() = ParallelContext(this)

    override fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>) {
        pipelinesInContainer.forEach { it.addPipelineGroupToGraph(graph) }
        if (forkPipeline != null) {
            pipelinesInContainer.forEach { toGroup ->
                forkPipeline.getEndingPipelines().forEach { from ->
                    toGroup.getStartingPipelines().forEach { to ->
                        graph.addEdge(from, to)
                    }
                }
            }
        }
    }

    override fun getStartingPipelines() = pipelinesInContainer.flatMap { it.getStartingPipelines() }
    override fun getEndingPipelines() = pipelinesInContainer.flatMap { it.getEndingPipelines() }

    fun sequence(init: PipelineSequence.() -> Unit): PipelineSequence {
        val pipelineSequence = PipelineSequence()
        pipelineSequence.init()

        this.pipelinesInContainer.add(pipelineSequence)
        return pipelineSequence
    }

    fun group(groupName: String, block: ParallelContext.() -> Unit): ParallelContext {
        return context({
            postProcessors.add { pipeline ->
                if (pipeline.group == null) {
                    pipeline.group = groupName
                }
            }
        }, block as Context.() -> Unit) as ParallelContext
    }
}

data class PipelineSingle(val name: String) : PipelineContainer() {
    val tags = mutableMapOf<String, String>()
    val definitionException = IllegalArgumentException(name)

    init {
        definitionException.fillInStackTrace()
        val filtered = definitionException.stackTrace.filter { !it.className.startsWith("net.oneandone.gocd.picodsl.dsl") }

        definitionException.stackTrace = filtered.toTypedArray()
    }

    fun tag(key: String, value: String) {
        tags[key] = value
    }

    var lockBehavior: LockBehavior = LockBehavior.unlockWhenFinished

    val lastStage: String
        get() {
            require(template != null || stages.size > 0) {
                "Pipeline has neither template nor stages"
            }
            return template?.stage ?: stages.last().name
        }

    var parameters = mutableMapOf<String, String>()
    var environmentVariables = mutableMapOf<String, String>()

    var template: Template? = null
    var group: String? = null

    // todo trennung zwischen Builder f. DSL und Objekt
    var stages: MutableList<Stage> = mutableListOf()
    var materials: Materials? = null

    override fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>) {
        graph.addVertex(this)
    }

    override fun runGraphProcessors(graph: Graph<PipelineSingle, DefaultEdge>) {
        graphProcessors.forEach { processor ->
            this.apply { processor(graph) }
        }
    }

    override fun getStartingPipelines() = listOf(this)
    override fun getEndingPipelines() = listOf(this)
    override fun getAllPipelines() = listOf(this)

    fun parameter(key: String, value: String) {
        parameters[key] = value
    }

    fun environment(key: String, value: String) {
        environmentVariables[key] = value
    }

    fun stage(name: String, manualApproval: Boolean = false, init: Stage.() -> Unit): Stage {
        val stage = Stage(name, manualApproval)
        stage.init()
        stages.add(stage)
        return stage
    }

    fun materials(init: Materials.() -> Unit): Materials {
        val materials = Materials()
        materials.init()
        this.materials = materials
        return materials
    }

    fun environment(key: String, value: Any) {
        environmentVariables[key] = value.toString()
    }
}

fun pathToPipeline(graph: Graph<PipelineSingle, DefaultEdge>, to: PipelineSingle, matcher: (PipelineSingle) -> Boolean): String {

    val candidates = graph.vertexSet().filter(matcher)
    val dijkstraAlg = DijkstraShortestPath(graph)

    val shortestPath = candidates.map { candidate ->
        val startPath = dijkstraAlg.getPaths(candidate)
        startPath.getPath(to).edgeList
    }.minBy { it.size } ?: throw IllegalArgumentException("no path found to $to", to.definitionException)

    return shortestPath.joinToString(separator = "/") { edge ->
        graph.getEdgeSource(edge).name
    }
}