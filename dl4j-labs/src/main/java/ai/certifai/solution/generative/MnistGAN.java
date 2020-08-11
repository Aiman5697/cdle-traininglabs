/*
 * Copyright (c) 2019 Skymind AI Bhd.
 * Copyright (c) 2020 CertifAI Sdn. Bhd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai.certifai.solution.generative;

import org.apache.commons.lang3.ArrayUtils;
import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.PerformanceListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.activations.impl.ActivationLReLU;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.learning.config.IUpdater;
import org.nd4j.linalg.learning.config.Sgd;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Arrays;

/**
 * Example of Mnist GAN in DL4J
 * Relatively small GAN example using only Dense layers with dropout to generate handwritten digits from MNIST data
 * credit to wmeddie, this example altered from the original source https://github.com/wmeddie/dl4j-gans/blob/master/src/main/java/io/skymind/example/App.java
 */
public class MnistGAN {
    private static final Logger log = LoggerFactory.getLogger(MnistGAN.class);

    private static final double LEARNING_RATE = 0.0002;
    private static final double GRADIENT_THRESHOLD = 100.0;
    private static final IUpdater UPDATER = Adam.builder().learningRate(LEARNING_RATE).beta1(0.5).build();
    private static final IUpdater UPDATER_ZERO = Sgd.builder().learningRate(0.0).build();
    private static int visualizationInterval = 10;

    public static void main(String... args) throws Exception {
        MnistDataSetIterator trainData = null;

        /* Step 1:
         * Load Mnist dataset
         */
        trainData = new MnistDataSetIterator(128, true, 42);

        /* Step 2:
         * Networks setup.
         * gen -> generate fake samples (network params from gan-gen)
         * dis -> train discriminator (network params to gan-dis)
         * gan -> train generator (with frozen gan-dis)
         */
        MultiLayerNetwork gen = new MultiLayerNetwork(generator());
        MultiLayerNetwork dis = new MultiLayerNetwork(discriminator());
        MultiLayerNetwork gan = new MultiLayerNetwork(gan());
        gen.init();
        dis.init();
        gan.init();
        // all parameters referring gan
        copyParams(gen, dis, gan);

        gen.setListeners(new PerformanceListener(10, true));
        dis.setListeners(new PerformanceListener(10, true));
        gan.setListeners(new PerformanceListener(10, true));

        /* Step 3:
         * Setup GAN Visualization, to visualize fake samples generated by GAN based on visualizationInterval (default: 10 iteration)
         */
        JFrame frame = GANVisualizationUtils.initFrame();
        int numSamples = 12;
        JPanel panel = GANVisualizationUtils.initPanel(frame, numSamples);

        while (true) {
            int j = 0;
            while (trainData.hasNext()) {
                j++;

                /* Step 4:
                 * load real samples from dataset
                 */
                INDArray real = trainData.next().getFeatures().muli(2).subi(1);
                int batchSize = (int)real.shape()[0];

                /* Step 5:
                 * generate fake samples with random noise
                 */
                INDArray fakeIn = Nd4j.randn(new int[]{batchSize,  100});
                INDArray fake = gen.output(fakeIn);

                /* Step 6:
                 * Format dataset with labels 0 and 1 before dis network training.
                 */
                DataSet realSet = new DataSet(real, Nd4j.zeros(batchSize, 1));
                DataSet fakeSet = new DataSet(fake, Nd4j.ones(batchSize, 1));
                DataSet data = DataSet.merge(Arrays.asList(realSet, fakeSet));

                /* Step 7:
                 * train dis
                 * fit/train discriminator twice in each iteration yield better result. (in this example)
                 */
                dis.fit(data);
                dis.fit(data);

                /* Step 8:
                 * update the gan-dis parameters after dis network training.
                 */
                updateGan(gen, dis, gan);

                /* Step 9:
                 * train gan with random noises (gan-dis is frozen)
                 */
                gan.fit(new DataSet(Nd4j.randn(new int[] { batchSize, 100}), Nd4j.zeros(batchSize, 1)));

                /* Step 10:
                 * copy parameters from GAN to Generator and Discriminator
                 */
                copyParams(gen, dis, gan);

                /* Step 11:
                 * visualize samples generated by generator
                 */
                if (j % visualizationInterval == 1) {
                    log.info("Iteration " + j + " Visualizing...");

                    INDArray[] samples = new INDArray[numSamples];
                    DataSet fakeSet2 = new DataSet(fakeIn, Nd4j.ones(batchSize, 1));

                    for (int k = 0; k < numSamples; k++) {
                        INDArray input = fakeSet2.get(k).getFeatures();
                        samples[k] =gen.output(input);
                    }
                    GANVisualizationUtils.visualize(samples, frame, panel);
                }
            }
            trainData.reset();
        }
    }

    /**
     * Returns a network config that takes in a 10x10 random number and produces a 28x28 grayscale image.
     * @return config
     */
    private static MultiLayerConfiguration generator() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(42)
                .updater(UPDATER)
                .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer)
                .gradientNormalizationThreshold(GRADIENT_THRESHOLD)
                .weightInit(WeightInit.XAVIER)
                .activation(Activation.IDENTITY)
                .list(genLayers())
                .build();

        return conf;
    }

    private static Layer[] genLayers() {
        return new Layer[] {
                new DenseLayer.Builder().nIn(100).nOut(256).weightInit(WeightInit.NORMAL).build(),
                new ActivationLayer.Builder(new ActivationLReLU(0.2)).build(),
                new DenseLayer.Builder().nIn(256).nOut(512).build(),
                new ActivationLayer.Builder(new ActivationLReLU(0.2)).build(),
                new DenseLayer.Builder().nIn(512).nOut(1024).build(),
                new ActivationLayer.Builder(new ActivationLReLU(0.2)).build(),
                new DenseLayer.Builder().nIn(1024).nOut(784).activation(Activation.TANH).build()
        };
    }

    /**
     * Returns a network config that takes in a 28x28 grayscale (dataset or image generated by generator) and
     * output binary classification (real or fake image).
     * @return config
     */
    private static MultiLayerConfiguration discriminator() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(42)
                .updater(UPDATER)
                .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer)
                .gradientNormalizationThreshold(GRADIENT_THRESHOLD)
                .weightInit(WeightInit.XAVIER)
                .activation(Activation.IDENTITY)
                .list(disLayers(UPDATER))
                .build();

        return conf;
    }

    private static Layer[] disLayers(IUpdater updater) {
        return new Layer[] {
                new DenseLayer.Builder().nIn(784).nOut(1024).updater(updater).build(),
                new ActivationLayer.Builder(new ActivationLReLU(0.2)).build(),
                new DropoutLayer.Builder(1 - 0.5).build(),
                new DenseLayer.Builder().nIn(1024).nOut(512).updater(updater).build(),
                new ActivationLayer.Builder(new ActivationLReLU(0.2)).build(),
                new DropoutLayer.Builder(1 - 0.5).build(),
                new DenseLayer.Builder().nIn(512).nOut(256).updater(updater).build(),
                new ActivationLayer.Builder(new ActivationLReLU(0.2)).build(),
                new DropoutLayer.Builder(1 - 0.5).build(),
                new OutputLayer.Builder(LossFunctions.LossFunction.XENT).nIn(256).nOut(1).activation(Activation.SIGMOID).updater(updater).build()
        };
    }

    /**
     * Returns a combined network GAN config (Generator + Discriminator)
     * Discriminator layers are freeze with UPDATER_ZERO
     * This network is for Generator training.
     * @return config
     */
    private static MultiLayerConfiguration gan() {
        Layer[] genLayers = genLayers();
        Layer[] disLayers = disLayers(UPDATER_ZERO); // Freeze discriminator layers in combined network.
        Layer[] layers = ArrayUtils.addAll(genLayers, disLayers);

        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(42)
                .updater(UPDATER)
                .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer)
                .gradientNormalizationThreshold(GRADIENT_THRESHOLD)
                .weightInit(WeightInit.XAVIER)
                .activation(Activation.IDENTITY)
                .list(layers)
                .build();

        return conf;
    }

    /**
     * copy parameters from GAN to Generator and Discriminator
     * @param gen
     * @param dis
     * @param gan
     */
    private static void copyParams(MultiLayerNetwork gen, MultiLayerNetwork dis, MultiLayerNetwork gan) {
        int genLayerCount = gen.getLayers().length;
        for (int i = 0; i < gan.getLayers().length; i++) {
            if (i < genLayerCount) {
                gen.getLayer(i).setParams(gan.getLayer(i).params());
            } else {
                // this will not affect anything, since gan-dis parameters are shallow copy and originated from dis
                dis.getLayer(i - genLayerCount).setParams(gan.getLayer(i).params());
            }
        }
    }

    /**
     * update the discriminator parameters in GAN
     * @param gen
     * @param dis
     * @param gan
     */
    private static void updateGan(MultiLayerNetwork gen, MultiLayerNetwork dis, MultiLayerNetwork gan) {
        int genLayerCount = gen.getLayers().length;
        for (int i = genLayerCount; i < gan.getLayers().length; i++) {
            gan.getLayer(i).setParams(dis.getLayer(i - genLayerCount).params());
        }
    }
}