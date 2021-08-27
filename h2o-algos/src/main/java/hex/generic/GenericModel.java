package hex.generic;

import hex.*;
import hex.genmodel.*;
import hex.genmodel.algos.kmeans.KMeansMojoModel;
import hex.genmodel.descriptor.ModelDescriptor;
import hex.tree.isofor.ModelMetricsAnomaly;
import water.Futures;
import water.H2O;
import water.Iced;
import water.Key;
import water.fvec.ByteVec;
import water.fvec.Frame;
import water.util.Log;

import java.io.IOException;

public class GenericModel extends Model<GenericModel, GenericModelParameters, GenericModelOutput> {

    private MojoModelSource _mojoModelSource;

    /**
     * Full constructor
     *
     */
    public GenericModel(Key<GenericModel> selfKey, GenericModelParameters parms, GenericModelOutput output, MojoModel mojoModel, Key<Frame> mojoSource) {
        super(selfKey, parms, output);
        _mojoModelSource = new MojoModelSource(mojoSource, mojoModel);
        _output = new GenericModelOutput(mojoModel._modelDescriptor, mojoModel._modelAttributes, mojoModel._reproducibilityInformation);
        if(mojoModel._modelAttributes != null && mojoModel._modelAttributes.getModelParameters() != null) {
            _parms._modelParameters = GenericModelParameters.convertParameters(mojoModel._modelAttributes.getModelParameters());
        }

    }

    private static MojoModel reconstructMojo(ByteVec mojoBytes) {
        try {
            final MojoReaderBackend readerBackend = MojoReaderBackendFactory.createReaderBackend(mojoBytes.openStream(null), MojoReaderBackendFactory.CachingStrategy.MEMORY);
            return ModelMojoReader.readFrom(readerBackend, true);
        } catch (IOException e) {
            throw new IllegalStateException("Unreachable MOJO file: " + mojoBytes._key, e);
        }
    }

    @Override
    public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
        switch(_output.getModelCategory()) {
            case Unknown:
                throw new IllegalStateException("Model category is unknown");
            case Binomial:
                return new ModelMetricsBinomial.MetricBuilderBinomial(domain);
            case Multinomial:
                return new ModelMetricsMultinomial.MetricBuilderMultinomial(_output.nclasses(), domain, _parms._auc_type);
            case Ordinal:
                return new ModelMetricsOrdinal.MetricBuilderOrdinal(_output.nclasses(), domain);
            case Regression:  return new ModelMetricsRegression.MetricBuilderRegression();
            case Clustering:
                if (mojoModel() instanceof KMeansMojoModel) {
                    KMeansMojoModel kMeansMojoModel = (KMeansMojoModel) mojoModel();
                    return new ModelMetricsClustering.MetricBuilderClustering(_output.nfeatures(), kMeansMojoModel.getNumClusters());
                } else {
                    return unsupportedMetricsBuilder();
                }
            case AutoEncoder:
                return new ModelMetricsAutoEncoder.MetricBuilderAutoEncoder(_output.nfeatures());
            case DimReduction:
                return unsupportedMetricsBuilder();
            case WordEmbedding:
                return unsupportedMetricsBuilder();
            case CoxPH:
                return new ModelMetricsRegressionCoxPH.MetricBuilderRegressionCoxPH("start", "stop", false, new String[0]);
            case AnomalyDetection:
                return new ModelMetricsAnomaly.MetricBuilderAnomaly();
            default:
                throw H2O.unimpl();
        }
    }
    
    private ModelMetrics.MetricBuilder unsupportedMetricsBuilder() {
        if (_parms._disable_algo_check) {
            Log.warn("Model category `" + _output._modelCategory + "` currently doesn't support calculating model metrics. " +
                    "Model metrics will not be available.");
            return new MetricBuilderGeneric(mojoModel().getPredsSize(_output._modelCategory));
        } else {
            throw new UnsupportedOperationException(_output._modelCategory + " is not supported.");
        }
    }
    
    @Override
    protected double[] score0(double[] data, double[] preds) {
        return mojoModel().score0(data, preds);
    }

    @Override
    protected double[] score0(double[] data, double[] preds, double offset) {
        if (offset == 0) // MOJO doesn't like when score0 is called with 0 offset for problems that were trained without offset 
            return score0(data, preds);
        else
            return mojoModel().score0(data, offset, preds);
    }

    @Override
    protected AdaptFrameParameters makeAdaptFrameParameters() {
        final MojoModel mojoModel = mojoModel();
        CategoricalEncoding encoding = mojoModel.getCategoricalEncoding();
        if (encoding.isParametrized()) {
            throw new UnsupportedOperationException(
                    "Models with categorical encoding '" + encoding + "' are not currently supported for predicting and/or calculating metrics.");
        }
        final Parameters.CategoricalEncodingScheme encodingScheme = Parameters.CategoricalEncodingScheme.fromGenModel(encoding);
        final ModelDescriptor descriptor = mojoModel._modelDescriptor;
        return new AdaptFrameParameters() {
            @Override
            public Parameters.CategoricalEncodingScheme getCategoricalEncoding() {
                return encodingScheme;
            }
            @Override
            public String getWeightsColumn() {
                return descriptor.weightsColumn();
            }
            @Override
            public String getOffsetColumn() {
                return descriptor.offsetColumn();
            }
            @Override
            public String getFoldColumn() {
                return descriptor.foldColumn();
            }
            @Override
            public String getResponseColumn() {
                return mojoModel.isSupervised() ? mojoModel.getResponseName() : null; 
            }
            @Override
            public double missingColumnsType() {
                return Double.NaN;
            }
            @Override
            public int getMaxCategoricalLevels() {
                return -1; // returned but won't be used
            }
        };
    }

    @Override
    protected String[] makeScoringNames() {
        return mojoModel().getOutputNames();
    }

    @Override
    protected boolean needsPostProcess() {
        return false; // MOJO scoring includes post-processing 
    }

    @Override
    public GenericModelMojoWriter getMojo() {
        return new GenericModelMojoWriter(_mojoModelSource.mojoByteVec());
    }

    private MojoModel mojoModel() {
        return _mojoModelSource.get();
    }

    private static class MetricBuilderGeneric extends ModelMetrics.MetricBuilder<MetricBuilderGeneric> {
        private MetricBuilderGeneric(int predsSize) {
            _work = new double[predsSize];
        }

        @Override
        public double[] perRow(double[] ds, float[] yact, Model m) {
            return ds;
        }

        @Override
        public ModelMetrics makeModelMetrics(Model m, Frame f, Frame adaptedFrame, Frame preds) {
            return null;
        }
    }

    @Override
    protected Futures remove_impl(Futures fs, boolean cascade) {
        if (_parms._path != null) {
            // user loaded the model by providing a path (not a Frame holding MOJO data) => we need to do the clean-up
            Frame mojoFrame = _mojoModelSource._mojoSource.get();
            if (mojoFrame != null) {
                mojoFrame.remove(fs, cascade);
            }
        }
        return super.remove_impl(fs, cascade);
    }

    private static class MojoModelSource extends Iced<MojoModelSource> {
        private Key<Frame> _mojoSource;

        private transient MojoModel _mojoModel;

        MojoModelSource(Key<Frame> mojoSource, MojoModel mojoModel) {
            _mojoSource = mojoSource;
            _mojoModel = mojoModel;
        }

        private ByteVec mojoByteVec() {
            return (ByteVec) _mojoSource.get().anyVec();
        }

        MojoModel get() {
            if (_mojoModel == null) {
                synchronized (this) {
                    if (_mojoModel == null) {
                        _mojoModel = reconstructMojo(mojoByteVec());
                    }
                }
            }
            assert _mojoModel != null;
            return _mojoModel;
        }
    }

}
