package jps

internal object Samples {
    val sha: Sha256 = Sha256("a".repeat(64))

    fun record(): Record =
        Record(
            id = "step:demo.RetryStep",
            kind = RecordKind.STEP,
            extensionClass = ClassName("demo/RetryStep\$DescriptorImpl"),
            describableClass = ClassName("demo/RetryStep"),
            name = "retry",
            symbols = listOf("retry"),
            confidence = Confidence.EXACT,
            evidence = listOf("index:hudson.Extension", "bytecode:getFunctionName"),
            reason = null,
            gaps = listOf(Gap("step.required_context", "unsupported-idiom", "branching method")),
            step = StepFacts(takesBlock = true, requiredContext = listOf(ClassName("hudson/model/Run")), advanced = false, metastep = false),
            argsRewritten = false,
            dynamicDispatch = false,
            optionalExtension = false,
            declarativeDirectives = emptyList(),
            extension = ExtensionFacts("hudson.Extension", -1.0, false, emptyList(), null),
            parameters =
                listOf(
                    Parameter(
                        "count",
                        TypeNode(TypeCategory.ATOMIC, "int"),
                        modelRequired = true,
                        origin = "constructor",
                        namesSource = "stapler",
                    ),
                    Parameter(
                        "conditions",
                        TypeNode(
                            TypeCategory.ARRAY,
                            "java.util.List<demo.Cond>",
                            element = TypeNode(TypeCategory.OBJECT, "demo.Cond", nested = ClassName("demo/Cond")),
                        ),
                        modelRequired = false,
                        usageHint = UsageHint.NULLABLE,
                        usageHintEvidence = "CheckForNull on field conditions",
                        origin = "setter-field",
                    ),
                ),
            docs = Docs(DocsStatus.PRESENT, "Retry the body.", "<p>Retry the body.</p>", "Retry the body.", "en", DocsSource("help-html", "demo/RetryStep/help.html")),
            links = listOf(Link("plugin", "https://plugins.jenkins.io/demo/")),
        )

    fun surface(records: List<Record> = listOf(record())): PluginSurface =
        PluginSurface(
            plugin = PluginIdentity(PluginId("demo"), "1.0", sha, "2.400", listOf(Dependency(PluginId("structs"), "1.0", false))),
            generator =
                GeneratorInfo(
                    TOOL_VERSION,
                    listOf("bytecode-scan", "index-reader"),
                    InputIdentity("jenkins-core", "2.400", sha),
                    listOf(InputIdentity("structs", "1.0", sha)),
                ),
            records = records,
            census =
                Census(
                    extensionsSeen = records.size,
                    classified = records.size,
                    unknown = 0,
                    notPipelineFacing = 0,
                    jars =
                        listOf(
                            JarCensus(
                                "WEB-INF/lib/demo.jar",
                                listOf(IndexFileCensus("META-INF/annotations/hudson.Extension.txt", "hudson.Extension", "sezpoz-txt", 1, 1, emptyList(), emptyList())),
                            ),
                        ),
                ),
        )
}
