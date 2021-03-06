/*
 * Copyright 2019 The FATE Authors. All Rights Reserved.
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

package com.webank.ai.fate.serving.federatedml;


import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.webank.ai.fate.core.mlmodel.buffer.PipelineProto;
import com.webank.ai.fate.serving.core.bean.*;
import com.webank.ai.fate.serving.federatedml.model.BaseModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.webank.ai.fate.serving.core.bean.Dict.PIPLELINE_IN_MODEL;

public class PipelineTask {
    private static final Logger logger = LoggerFactory.getLogger(PipelineTask.class);
    private List<BaseModel> pipeLineNode = new ArrayList<>();
    private Map<String, BaseModel> modelMap = new HashMap<String, BaseModel>();
    private DSLParser dslParser = new DSLParser();
    private String modelPackage = "com.webank.ai.fate.serving.federatedml.model";
    public BaseModel getModelByComponentName(String name) {
        return this.modelMap.get(name);
    }
    public int initModel(Map<String, byte[]> modelProtoMap) {
        if(modelProtoMap!=null) {
            logger.info("start init pipeline,model components {}", modelProtoMap.keySet());
            try {
                Map<String, byte[]> newModelProtoMap = changeModelProto(modelProtoMap);
                logger.info("after parse pipeline {}", newModelProtoMap.keySet());
                Preconditions.checkArgument(newModelProtoMap.get(PIPLELINE_IN_MODEL) != null);
                PipelineProto.Pipeline pipeLineProto = PipelineProto.Pipeline.parseFrom(newModelProtoMap.get(PIPLELINE_IN_MODEL));
                //inference_dsl
                String dsl = pipeLineProto.getInferenceDsl().toStringUtf8();
                dslParser.parseDagFromDSL(dsl);
                ArrayList<String> components = dslParser.getAllComponent();
                HashMap<String, String> componentModuleMap = dslParser.getComponentModuleMap();

                for (int i = 0; i < components.size(); ++i) {
                    String componentName = components.get(i);
                    String className = componentModuleMap.get(componentName);
                    logger.info("try to get class:{}", className);
                    try {
                        Class modelClass = Class.forName(this.modelPackage + "." + className);
                        BaseModel mlNode = (BaseModel) modelClass.getConstructor().newInstance();
                        mlNode.setComponentName(componentName);
                        byte[] protoMeta = newModelProtoMap.get(componentName + ".Meta");
                        byte[] protoParam = newModelProtoMap.get(componentName + ".Param");
                        int returnCode = mlNode.initModel(protoMeta, protoParam);
                        if (returnCode == StatusCode.OK) {
                            modelMap.put(componentName, mlNode);
                            pipeLineNode.add(mlNode);
                            logger.info(" Add class {} to pipeline task list", className);
                        } else {
                            throw new RuntimeException("initModel error");
                        }
                    } catch (Exception ex) {
                        pipeLineNode.add(null);
                        logger.warn("Can not instance {} class", className);
                    }
                }
            } catch (Exception ex) {
                // ex.printStackTrace();
                logger.info("PipelineTask initModel error:{}", ex);
                throw new RuntimeException("initModel error");
            }
            logger.info("Finish init Pipeline");
            return StatusCode.OK;
        }else{
            logger.error("model content is null ");
            throw new RuntimeException("model content is null");
        }
    }


    public Map<String, Object> predict(Context context, Map<String, Object> inputData, FederatedParams predictParams) {
        //logger.info("Start Pipeline predict use {} model node.", this.pipeLineNode.size());
        context.putData(Dict.ORIGINAL_PREDICT_DATA, inputData);
        List<Map<String, Object>> outputData = Lists.newArrayList();

        List<Map<String,Object>>  result = Lists.newArrayList();
        int pipelineSize = this.pipeLineNode.size();
        for (int i = 0; i < pipelineSize; i++) {
            if(logger.isDebugEnabled()) {
                if (this.pipeLineNode.get(i) != null) {
                    logger.debug("component class is {}", this.pipeLineNode.get(i).getClass().getName());
                } else {
                    logger.debug("component class is {}", this.pipeLineNode.get(i));
                }
            }
            List<Map<String, Object>> inputs = new ArrayList<>();
            HashSet<Integer> upInputComponents = this.dslParser.getUpInputComponents(i);
            if (upInputComponents != null) {
                Iterator<Integer> iters = upInputComponents.iterator();
                while (iters.hasNext()) {
                    Integer upInput = iters.next();
                    if (upInput == -1) {
                        inputs.add(inputData);
                    } else {
                        inputs.add(outputData.get(upInput));
                    }
                }
            } else {
                inputs.add(inputData);
            }
            if (this.pipeLineNode.get(i) != null) {
                Map<String, Object>  modelResult = this.pipeLineNode.get(i).handlePredict(context, inputs, predictParams);
                outputData.add(modelResult);
                result.add(modelResult);

            } else {
                outputData.add(inputs.get(0));

            }

        }
        ReturnResult federatedResult = context.getFederatedResult();
        if (federatedResult != null) {
            inputData.put(Dict.RET_CODE, federatedResult.getRetcode());
        }
        if(result.size()>0){
            return result.get(result.size() - 1);
        }else{
            return Maps.newHashMap();
        }


    }

    private HashMap<String, byte[]> changeModelProto(Map<String, byte[]> modelProtoMap) {
        HashMap<String, byte[]> newModelProtoMap = new HashMap<String, byte[]>(8);
        for (Map.Entry<String, byte[]> entry : modelProtoMap.entrySet()) {
            String key = entry.getKey();
            if (!"pipeline.pipeline:Pipeline".equals(key)) {
                String[] componentNameSegments = key.split("\\.", -1);
                if (componentNameSegments.length != 2) {
                    newModelProtoMap.put(entry.getKey(), entry.getValue());
                    continue;
                }

                if (componentNameSegments[1].endsWith("Meta")) {
                    newModelProtoMap.put(componentNameSegments[0] + ".Meta", entry.getValue());
                } else if (componentNameSegments[1].endsWith("Param")) {
                    newModelProtoMap.put(componentNameSegments[0] + ".Param", entry.getValue());
                }
            } else {
                newModelProtoMap.put(entry.getKey(), entry.getValue());
            }
        }

        return newModelProtoMap;
    }

    public void collectExpectDataList(Context context) {
        if (this.pipeLineNode == null) {
            return;
        }
        for (int i = 0; i < this.pipeLineNode.size(); i++) {
            BaseModel node = this.pipeLineNode.get(i);
            if (node != null) {
                List<String> weightKeys = node.getWeightKeys();
                List<String> expectDataList = (List<String>) context.getData(Dict.EXPECT_DATA_LIST);
                if (expectDataList != null) {
                    if (weightKeys != null) {
                        expectDataList.addAll(node.getWeightKeys());
                        context.putData(Dict.EXPECT_DATA_LIST, expectDataList);
                    }
                } else {
                    context.putData(Dict.EXPECT_DATA_LIST, weightKeys);
                }
            }
        }
    }
}
