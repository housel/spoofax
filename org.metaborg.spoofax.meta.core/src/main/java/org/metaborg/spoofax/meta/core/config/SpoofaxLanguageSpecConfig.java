package org.metaborg.spoofax.meta.core.config;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ImmutableConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.metaborg.core.config.IExportConfig;
import org.metaborg.core.config.IGenerateConfig;
import org.metaborg.core.language.LanguageContributionIdentifier;
import org.metaborg.core.language.LanguageIdentifier;
import org.metaborg.core.messages.IMessage;
import org.metaborg.core.messages.MessageBuilder;
import org.metaborg.core.project.NameUtil;
import org.metaborg.meta.core.config.LanguageSpecConfig;
import org.metaborg.util.cmd.Arguments;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;

import com.google.common.collect.Lists;

/**
 * An implementation of the {@link ISpoofaxLanguageSpecConfig} interface that is backed by an
 * {@link ImmutableConfiguration} object.
 */
public class SpoofaxLanguageSpecConfig extends LanguageSpecConfig implements ISpoofaxLanguageSpecConfig {
    private static final ILogger logger = LoggerUtils.logger(SpoofaxLanguageSpecConfig.class);

    private static final String PROP_SDF = "language.sdf";
    private static final String PROP_SDF_VERSION = PROP_SDF + ".version";
    private static final String PROP_SDF_EXTERNAL_DEF = PROP_SDF + ".externalDef";
    private static final String PROP_SDF_ARGS = PROP_SDF + ".args";

    private static final String PROP_STR = "language.stratego";
    private static final String PROP_STR_FORMAT = PROP_STR + ".format";
    private static final String PROP_STR_EXTERNAL_JAR = PROP_STR + ".externalJar.name";
    private static final String PROP_STR_EXTERNAL_JAR_FLAGS = PROP_STR + ".externalJar.flags";
    private static final String PROP_STR_ARGS = PROP_STR + ".args";

    private static final LanguageSpecBuildPhase defaultPhase = LanguageSpecBuildPhase.preJava;
    private static final String PROP_BUILD = "build";
    private static final String PROP_BUILD_ANT = PROP_BUILD + ".ant";
    private static final String PROP_BUILD_STR = PROP_BUILD + ".stratego-cli";


    public SpoofaxLanguageSpecConfig(HierarchicalConfiguration<ImmutableNode> config) {
        super(config);
    }

    protected SpoofaxLanguageSpecConfig(final HierarchicalConfiguration<ImmutableNode> config, LanguageIdentifier id,
        String name, Collection<LanguageIdentifier> compileDeps, Collection<LanguageIdentifier> sourceDeps,
        Collection<LanguageIdentifier> javaDeps, Collection<LanguageContributionIdentifier> langContribs,
        Collection<IGenerateConfig> generates, Collection<IExportConfig> exports, String metaborgVersion,
        Collection<String> pardonedLanguages, boolean useBuildSystemSpec, SdfVersion sdfVersion, String externalDef,
        Arguments sdfArgs, StrategoFormat format, String externalJar, String externalJarFlags, Arguments strategoArgs,
        Collection<IBuildStepConfig> buildSteps) {
        super(config, id, name, compileDeps, sourceDeps, javaDeps, langContribs, generates, exports, metaborgVersion,
            pardonedLanguages, useBuildSystemSpec);

        config.setProperty(PROP_SDF_VERSION, sdfVersion);
        config.setProperty(PROP_SDF_EXTERNAL_DEF, externalDef);
        config.setProperty(PROP_SDF_ARGS, sdfArgs);

        config.setProperty(PROP_STR_FORMAT, format);
        config.setProperty(PROP_STR_EXTERNAL_JAR, externalJar);
        config.setProperty(PROP_STR_EXTERNAL_JAR_FLAGS, externalJarFlags);
        config.setProperty(PROP_STR_ARGS, strategoArgs);

        for(IBuildStepConfig buildStep : buildSteps) {
            buildStep.accept(new IBuildStepVisitor() {
                @Override public void visit(AntBuildStepConfig buildStep) {
                    config.addProperty(PROP_BUILD_ANT, buildStep);
                }

                @Override public void visit(StrategoBuildStepConfig buildStep) {
                    config.addProperty(PROP_BUILD_STR, buildStep);
                }
            });
        }
    }


    @Override public SdfVersion sdfVersion() {
        final String value = this.config.getString(PROP_SDF_VERSION);
        return value != null ? SdfVersion.valueOf(value) : SdfVersion.sdf3;
    }

    @Nullable public String sdfExternalDef() {
        return config.getString(PROP_SDF_EXTERNAL_DEF);
    }

    public Arguments sdfArgs() {
        final List<String> values = config.getList(String.class, PROP_SDF_ARGS);
        final Arguments arguments = new Arguments();
        if(values != null) {
            for(String value : values) {
                arguments.add(value);
            }
        }
        return arguments;
    }


    public StrategoFormat strFormat() {
        final String value = this.config.getString(PROP_STR_FORMAT);
        return value != null ? StrategoFormat.valueOf(value) : StrategoFormat.ctree;
    }

    @Override public @Nullable String strExternalJar() {
        return config.getString(PROP_STR_EXTERNAL_JAR);
    }

    @Override public @Nullable String strExternalJarFlags() {
        return config.getString(PROP_STR_EXTERNAL_JAR_FLAGS);
    }

    @Override public Arguments strArgs() {
        @Nullable final List<String> values = config.getList(String.class, PROP_STR_ARGS);
        final Arguments arguments = new Arguments();
        if(values != null) {
            for(String value : values) {
                arguments.add(value);
            }
        }
        return arguments;
    }


    @Override public Collection<IBuildStepConfig> buildSteps() {
        final List<HierarchicalConfiguration<ImmutableNode>> antConfigs = config.configurationsAt(PROP_BUILD_ANT);
        final List<HierarchicalConfiguration<ImmutableNode>> strConfigs = config.configurationsAt(PROP_BUILD_STR);
        final List<IBuildStepConfig> buildSteps = Lists.newArrayListWithCapacity(antConfigs.size() + strConfigs.size());

        for(HierarchicalConfiguration<ImmutableNode> antConfig : antConfigs) {
            final LanguageSpecBuildPhase phase = phase(antConfig);
            final String file = antConfig.getString("file");
            final String target = antConfig.getString("target");
            if(file != null && target != null) {
                buildSteps.add(new AntBuildStepConfig(phase, file, target));
            }
        }
        for(HierarchicalConfiguration<ImmutableNode> strConfig : strConfigs) {
            final LanguageSpecBuildPhase phase = phase(strConfig);
            final String strategy = strConfig.getString("strategy");
            final List<String> argStrs = strConfig.getList(String.class, "args", Lists.<String>newArrayList());
            final Arguments arguments = new Arguments();
            for(String argStr : argStrs) {
                arguments.add(argStr);
            }
            if(strategy != null) {
                buildSteps.add(new StrategoBuildStepConfig(phase, strategy, arguments));
            }
        }

        return buildSteps;
    }

    private LanguageSpecBuildPhase phase(HierarchicalConfiguration<ImmutableNode> config) {
        final String phaseStr = config.getString("phase");
        try {
            return phaseStr != null ? LanguageSpecBuildPhase.valueOf(phaseStr) : defaultPhase;
        } catch(IllegalArgumentException e) {
            logger.warn("Language specification build phase with name {} does not exist, defaulting to {}", e, phaseStr,
                defaultPhase);
            return defaultPhase;
        }
    }


    @Override public String esvName() {
        return name();
    }

    @Override public String sdfName() {
        return name();
    }

    @Override public String metaSdfName() {
        return sdfName() + "-Stratego";
    }

    @Override public String strategoName() {
        return NameUtil.toJavaId(name().toLowerCase());
    }

    @Override public String packageName() {
        return NameUtil.toJavaId(identifier().id);
    }

    @Override public String strategiesPackageName() {
        return packageName() + ".strategies";
    }

    @Override public String javaName() {
        return NameUtil.toJavaId(name());
    }


    public Collection<IMessage> validate(MessageBuilder mb) {
        final Collection<IMessage> messages = super.validate(mb);

        // TODO: validate sdf version
        // TODO: validate Stratego format
        // TODO: validate buildSteps

        return messages;
    }
}