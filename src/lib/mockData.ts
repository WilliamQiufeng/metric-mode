import type {
  ChecklistSnapshot,
  CandidateModel,
  DatasetInfo,
  ChatMessage,
  Prediction,
  ResultsSummary,
} from '@/types/workflow';

export const createInitialChecklist = (): ChecklistSnapshot => ({
  inputType: { key: 'inputType', label: 'Input Type', value: null, confirmed: false, required: true },
  outputType: { key: 'outputType', label: 'Output Type', value: null, confirmed: false, required: true },
  trainingType: { key: 'trainingType', label: 'Training Type', value: null, confirmed: false, required: true },
  dataPath: { key: 'dataPath', label: 'Data Path', value: null, confirmed: false, required: true },
  splitStrategy: { key: 'splitStrategy', label: 'Split Strategy', value: null, confirmed: false, required: true },
  metric: { key: 'metric', label: 'Metric', value: null, confirmed: false, required: true },
  predictionDataPath: { key: 'predictionDataPath', label: 'Prediction Data Path', value: null, confirmed: false, required: true },
  modelFamilyCategory: { key: 'modelFamilyCategory', label: 'Model Family Category', value: null, confirmed: false, required: false },
});

export const mockDataset: DatasetInfo = {
  datasetId: 'dataset-001',
  filename: 'experiment_data.csv',
  columns: [
    { name: 'sample_id', type: 'text', missingCount: 0, uniqueCount: 150 },
    { name: 'feature_1', type: 'numeric', missingCount: 3, uniqueCount: 145 },
    { name: 'feature_2', type: 'numeric', missingCount: 0, uniqueCount: 89 },
    { name: 'feature_3', type: 'categorical', missingCount: 5, uniqueCount: 4 },
    { name: 'experiment_date', type: 'datetime', missingCount: 0, uniqueCount: 30 },
    { name: 'target', type: 'numeric', missingCount: 0, uniqueCount: 2 },
  ],
  previewRows: [
    { sample_id: 'S001', feature_1: 1.23, feature_2: 4.56, feature_3: 'A', experiment_date: '2024-01-15', target: 1 },
    { sample_id: 'S002', feature_1: 2.34, feature_2: 5.67, feature_3: 'B', experiment_date: '2024-01-15', target: 0 },
    { sample_id: 'S003', feature_1: null, feature_2: 6.78, feature_3: 'A', experiment_date: '2024-01-16', target: 1 },
    { sample_id: 'S004', feature_1: 3.45, feature_2: 7.89, feature_3: 'C', experiment_date: '2024-01-16', target: 0 },
    { sample_id: 'S005', feature_1: 4.56, feature_2: 8.90, feature_3: null, experiment_date: '2024-01-17', target: 1 },
  ],
  rowCount: 150,
  columnCount: 6,
  missingSummary: [
    { column: 'feature_1', count: 3, percentage: 2 },
    { column: 'feature_3', count: 5, percentage: 3.33 },
  ],
};

export const mockCandidates: CandidateModel[] = [
  { index: 0, library: 'scikit-learn', modelId: 'RandomForestClassifier', status: 'success', metricValue: 0.92, stdout: 'Training complete. Accuracy: 0.92', stderr: '' },
  { index: 1, library: 'scikit-learn', modelId: 'GradientBoostingClassifier', status: 'success', metricValue: 0.89, stdout: 'Training complete. Accuracy: 0.89', stderr: '' },
  { index: 2, library: 'xgboost', modelId: 'XGBClassifier', status: 'success', metricValue: 0.94, stdout: 'Training complete. Accuracy: 0.94', stderr: '' },
  { index: 3, library: 'lightgbm', modelId: 'LGBMClassifier', status: 'failed', stdout: '', stderr: 'Error: Feature names mismatch' },
];

export const mockInitialMessages: ChatMessage[] = [
  {
    id: 'msg-1',
    role: 'assistant',
    content: `Welcome to **Metric Mode**! I'm here to help you build a machine learning pipeline for your experimental data.

To get started, please:
1. **Upload your dataset** (CSV format) using the panel on the right
2. **Describe your research goal** — What are you trying to predict or classify?

Once I understand your data and objectives, I'll guide you through configuring the optimal ML workflow.`,
    timestamp: new Date(),
  },
];

export const mockModelPy = `"""
Auto-generated model.py for experiment_data.csv
Target: target (Classification)
Model: XGBClassifier
"""

import pandas as pd
import numpy as np
from xgboost import XGBClassifier
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.metrics import accuracy_score, classification_report

def load_and_preprocess(filepath: str) -> tuple:
    """Load and preprocess the dataset."""
    df = pd.read_csv(filepath)
    
    # Handle missing values
    df = df.dropna(subset=['feature_1', 'feature_3'])
    
    # Encode categorical variables
    le = LabelEncoder()
    df['feature_3_encoded'] = le.fit_transform(df['feature_3'])
    
    # Select features
    feature_cols = ['feature_1', 'feature_2', 'feature_3_encoded']
    X = df[feature_cols]
    y = df['target']
    
    return X, y, le

def train_model(X, y, random_state=42):
    """Train the XGBClassifier model."""
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=random_state, stratify=y
    )
    
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)
    
    model = XGBClassifier(
        n_estimators=100,
        max_depth=5,
        learning_rate=0.1,
        random_state=random_state
    )
    
    model.fit(X_train_scaled, y_train)
    
    return model, scaler, X_test_scaled, y_test

def evaluate_model(model, X_test, y_test):
    """Evaluate the trained model."""
    y_pred = model.predict(X_test)
    accuracy = accuracy_score(y_test, y_pred)
    report = classification_report(y_test, y_pred)
    
    return accuracy, report

if __name__ == "__main__":
    X, y, le = load_and_preprocess("experiment_data.csv")
    model, scaler, X_test, y_test = train_model(X, y)
    accuracy, report = evaluate_model(model, X_test, y_test)
    
    print(f"Accuracy: {accuracy:.4f}")
    print("\\nClassification Report:")
    print(report)
`;

export const mockTestPy = `"""
Auto-generated test.py for model validation
"""

import pytest
import numpy as np
from model import load_and_preprocess, train_model, evaluate_model

def test_load_and_preprocess():
    """Test data loading and preprocessing."""
    X, y, le = load_and_preprocess("experiment_data.csv")
    
    assert X is not None
    assert y is not None
    assert len(X) == len(y)
    assert X.shape[1] == 3  # 3 features

def test_train_model():
    """Test model training."""
    X, y, le = load_and_preprocess("experiment_data.csv")
    model, scaler, X_test, y_test = train_model(X, y)
    
    assert model is not None
    assert scaler is not None
    assert len(X_test) > 0

def test_model_accuracy():
    """Test model achieves minimum accuracy threshold."""
    X, y, le = load_and_preprocess("experiment_data.csv")
    model, scaler, X_test, y_test = train_model(X, y)
    accuracy, report = evaluate_model(model, X_test, y_test)
    
    assert accuracy >= 0.85, f"Model accuracy {accuracy} below threshold 0.85"

def test_prediction_output():
    """Test prediction output format."""
    X, y, le = load_and_preprocess("experiment_data.csv")
    model, scaler, X_test, y_test = train_model(X, y)
    
    predictions = model.predict(X_test)
    
    assert len(predictions) == len(y_test)
    assert all(p in [0, 1] for p in predictions)
`;

export const mockExplanationMd = `# Model Explanation

## Overview

This pipeline uses an **XGBClassifier** from the XGBoost library to predict the binary target variable based on your experimental data.

## Data Preprocessing

1. **Missing Value Handling**: Rows with missing values in \`feature_1\` and \`feature_3\` were dropped (8 rows removed, 5.3% of data).

2. **Categorical Encoding**: The \`feature_3\` column was label-encoded to convert categorical values (A, B, C, D) to numeric representations.

3. **Feature Scaling**: All numeric features were standardized using StandardScaler to ensure uniform scale across features.

## Model Selection

XGBClassifier was selected as the best performing model after evaluating 4 candidates:

| Model | Library | Accuracy |
|-------|---------|----------|
| XGBClassifier | xgboost | **0.94** |
| RandomForestClassifier | scikit-learn | 0.92 |
| GradientBoostingClassifier | scikit-learn | 0.89 |
| LGBMClassifier | lightgbm | Failed |

## Hyperparameters

The final model was trained with the following hyperparameters:

- **n_estimators**: 100
- **max_depth**: 5
- **learning_rate**: 0.1
- **random_state**: 42

## Feature Importance

Based on the trained model, the features contribute to predictions in the following order:

1. **feature_1** (42.3%): Strongest predictor
2. **feature_2** (35.1%): Secondary importance
3. **feature_3_encoded** (22.6%): Categorical feature contribution

## Recommendations

- Consider collecting more data to reduce the impact of missing values
- The model shows good generalization with 94% accuracy on held-out data
- Feature engineering on \`feature_1\` may further improve performance
`;

export const mockPredictions: Prediction[] = Array.from({ length: 50 }, (_, i) => ({
  id: i + 1,
  sample_id: `S${String(i + 151).padStart(3, '0')}`,
  feature_1: Math.round((Math.random() * 5 + 1) * 100) / 100,
  feature_2: Math.round((Math.random() * 5 + 4) * 100) / 100,
  feature_3: ['A', 'B', 'C', 'D'][Math.floor(Math.random() * 4)],
  predicted_target: Math.random() > 0.5 ? 1 : 0,
  confidence: Math.round((Math.random() * 0.3 + 0.7) * 100) / 100,
}));

export const mockResultsSummary: ResultsSummary = {
  selectedModel: {
    index: 2,
    library: 'xgboost',
    modelId: 'XGBClassifier',
    metricValue: 0.94,
  },
  evaluationMetric: {
    name: 'Accuracy',
    value: 0.94,
  },
  trainTestSplit: {
    trainSize: 120,
    testSize: 30,
    strategy: 'random',
  },
  hyperparameters: {
    n_estimators: 100,
    max_depth: 5,
    learning_rate: 0.1,
    random_state: 42,
  },
  confusionMatrix: [
    [12, 2],
    [1, 15],
  ],
};
