import { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { Send, Upload, FileSpreadsheet, ArrowRight, Loader2, Check, Database, BarChart3 } from 'lucide-react';
import type { ChatMessage, DatasetInfo } from '@/types/workflow';
import type { ChecklistSnapshot } from '@/types/api';
import { cn } from '@/lib/utils';

interface UploadedFile {
  filename: string;
  rowCount: number;
  columnCount: number;
}

interface Stage1IntakeProps {
  messages: ChatMessage[];
  dataset: DatasetInfo | null;
  predictionDataset: UploadedFile | null;
  checklist: ChecklistSnapshot;
  checklistOk: boolean;
  onSendMessage: (content: string) => Promise<unknown>;
  onUploadTrainData: (file: File) => Promise<DatasetInfo>;
  onUploadPredictionData: (file: File) => Promise<UploadedFile>;
  onProceed: () => void;
}

const Stage1Intake = ({
  messages,
  dataset,
  predictionDataset,
  checklist,
  checklistOk,
  onSendMessage,
  onUploadTrainData,
  onUploadPredictionData,
  onProceed,
}: Stage1IntakeProps) => {
  const [inputValue, setInputValue] = useState('');
  const [isUploadingTrain, setIsUploadingTrain] = useState(false);
  const [isUploadingPrediction, setIsUploadingPrediction] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [dragActiveTrain, setDragActiveTrain] = useState(false);
  const [dragActivePrediction, setDragActivePrediction] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const trainFileInputRef = useRef<HTMLInputElement>(null);
  const predictionFileInputRef = useRef<HTMLInputElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const handleSend = async () => {
    if (!inputValue.trim() || isSending) return;
    const message = inputValue.trim();
    setInputValue('');
    setIsSending(true);
    await onSendMessage(message);
    setIsSending(false);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleTrainFileUpload = async (file: File) => {
    if (!file.name.endsWith('.csv') && !file.name.endsWith('.parquet')) {
      alert('Please upload a CSV or Parquet file');
      return;
    }
    setIsUploadingTrain(true);
    await onUploadTrainData(file);
    setIsUploadingTrain(false);
  };

  const handlePredictionFileUpload = async (file: File) => {
    if (!file.name.endsWith('.csv') && !file.name.endsWith('.parquet')) {
      alert('Please upload a CSV or Parquet file');
      return;
    }
    setIsUploadingPrediction(true);
    await onUploadPredictionData(file);
    setIsUploadingPrediction(false);
  };

  const handleDragTrain = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActiveTrain(true);
    } else if (e.type === 'dragleave') {
      setDragActiveTrain(false);
    }
  }, []);

  const handleDragPrediction = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActivePrediction(true);
    } else if (e.type === 'dragleave') {
      setDragActivePrediction(false);
    }
  }, []);

  const handleDropTrain = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      setDragActiveTrain(false);
      if (e.dataTransfer.files && e.dataTransfer.files[0]) {
        handleTrainFileUpload(e.dataTransfer.files[0]);
      }
    },
    [onUploadTrainData]
  );

  const handleDropPrediction = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      setDragActivePrediction(false);
      if (e.dataTransfer.files && e.dataTransfer.files[0]) {
        handlePredictionFileUpload(e.dataTransfer.files[0]);
      }
    },
    [onUploadPredictionData]
  );

  // Quick configuration options for all 8 checklist items
  const quickOptions = {
    inputType: [
      { label: 'Tabular', message: 'Input type is tabular data' },
      { label: 'Text', message: 'Input type is text data' },
      { label: 'Image', message: 'Input type is image data' },
    ],
    outputType: [
      { label: 'Classification', message: 'This is a classification problem' },
      { label: 'Regression', message: 'This is a regression problem' },
    ],
    trainingType: [
      { label: 'Supervised', message: 'Use supervised learning' },
      { label: 'Semi-supervised', message: 'Use semi-supervised learning' },
    ],
    splitStrategy: [
      { label: 'Random 80/20', message: 'Use random 80/20 train-test split' },
      { label: 'Time-based', message: 'Use time-based split strategy' },
      { label: '5-Fold CV', message: 'Use 5-fold cross validation' },
    ],
    metric: [
      { label: 'Accuracy', message: 'Use accuracy as the metric', type: 'classification' },
      { label: 'F1 Score', message: 'Use F1 score as the metric', type: 'classification' },
      { label: 'AUC', message: 'Use AUC as the metric', type: 'classification' },
      { label: 'RMSE', message: 'Use RMSE as the metric', type: 'regression' },
      { label: 'MAE', message: 'Use MAE as the metric', type: 'regression' },
      { label: 'R²', message: 'Use R-squared as the metric', type: 'regression' },
    ],
    modelFamily: [
      { label: 'Tree-based', message: 'Use tree-based models (RandomForest, XGBoost, etc.)' },
      { label: 'Linear', message: 'Use linear models (Logistic/Linear Regression, etc.)' },
      { label: 'Neural Network', message: 'Use neural network models' },
      { label: 'All Families', message: 'Try all model families' },
    ],
  };

  const handleQuickOption = async (message: string) => {
    if (isSending) return;
    setIsSending(true);
    await onSendMessage(message);
    setIsSending(false);
  };

  return (
    <div className="flex h-[calc(100vh-140px)] flex-col lg:flex-row">
      {/* Chat Area - 70% on desktop */}
      <div className="flex flex-1 flex-col lg:w-[70%]">
        {/* Messages */}
        <ScrollArea className="flex-1 p-4">
          <div className="mx-auto max-w-2xl space-y-4">
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={cn(
                  'flex',
                  msg.role === 'user' ? 'justify-end' : 'justify-start'
                )}
              >
                <div
                  className={cn(
                    'max-w-[85%] rounded-lg px-4 py-3',
                    msg.role === 'user'
                      ? 'bg-primary text-primary-foreground'
                      : 'bg-card border border-border text-card-foreground'
                  )}
                >
                  <div className="whitespace-pre-wrap text-sm leading-relaxed">
                    {msg.content.split(/(\*\*.*?\*\*)/g).map((part, i) => {
                      if (part.startsWith('**') && part.endsWith('**')) {
                        return <strong key={i}>{part.slice(2, -2)}</strong>;
                      }
                      if (part.startsWith('`') && part.endsWith('`')) {
                        return (
                          <code key={i} className="rounded bg-muted px-1 py-0.5 font-mono text-xs">
                            {part.slice(1, -1)}
                          </code>
                        );
                      }
                      return part;
                    })}
                  </div>
                </div>
              </div>
            ))}
            <div ref={messagesEndRef} />
          </div>
        </ScrollArea>

        {/* Input */}
        <div className="border-t border-border bg-card p-4">
          {checklistOk ? (
            /* Configuration Complete - Show only proceed button */
            <div className="mx-auto max-w-2xl">
              <div className="mb-4 rounded-lg bg-primary/10 p-3 text-center text-sm text-primary">
                ✓ Configuration complete! Ready to proceed.
              </div>
              <Button onClick={onProceed} className="w-full" size="lg">
                Proceed to Stage 2
                <ArrowRight className="ml-2 h-4 w-4" />
              </Button>
            </div>
          ) : (
            /* Chat Input with Quick Options */
            <div className="mx-auto max-w-2xl space-y-3">
              {/* Quick Options */}
              <div className="space-y-2">
                {/* Input Type */}
                {!checklist.inputType.confirmed && (
                  <div className="flex flex-wrap gap-2">
                    <span className="text-xs text-muted-foreground self-center w-16">Input:</span>
                    {quickOptions.inputType.map((opt) => (
                      <Button
                        key={opt.label}
                        variant="outline"
                        size="sm"
                        onClick={() => handleQuickOption(opt.message)}
                        disabled={isSending}
                        className="h-7 text-xs"
                      >
                        {opt.label}
                      </Button>
                    ))}
                  </div>
                )}

                {/* Output Type (Task Type) */}
                {!checklist.outputType.confirmed && (
                  <div className="flex flex-wrap gap-2">
                    <span className="text-xs text-muted-foreground self-center w-16">Task:</span>
                    {quickOptions.outputType.map((opt) => (
                      <Button
                        key={opt.label}
                        variant="outline"
                        size="sm"
                        onClick={() => handleQuickOption(opt.message)}
                        disabled={isSending}
                        className="h-7 text-xs"
                      >
                        {opt.label}
                      </Button>
                    ))}
                  </div>
                )}

                {/* Training Type */}
                {!checklist.trainingType.confirmed && (
                  <div className="flex flex-wrap gap-2">
                    <span className="text-xs text-muted-foreground self-center w-16">Training:</span>
                    {quickOptions.trainingType.map((opt) => (
                      <Button
                        key={opt.label}
                        variant="outline"
                        size="sm"
                        onClick={() => handleQuickOption(opt.message)}
                        disabled={isSending}
                        className="h-7 text-xs"
                      >
                        {opt.label}
                      </Button>
                    ))}
                  </div>
                )}

                {/* Split Strategy */}
                {!checklist.splitStrategy.confirmed && (
                  <div className="flex flex-wrap gap-2">
                    <span className="text-xs text-muted-foreground self-center w-16">Split:</span>
                    {quickOptions.splitStrategy.map((opt) => (
                      <Button
                        key={opt.label}
                        variant="outline"
                        size="sm"
                        onClick={() => handleQuickOption(opt.message)}
                        disabled={isSending}
                        className="h-7 text-xs"
                      >
                        {opt.label}
                      </Button>
                    ))}
                  </div>
                )}
                
                {/* Metric - filter based on task type */}
                {!checklist.metric.confirmed && (
                  <div className="flex flex-wrap gap-2">
                    <span className="text-xs text-muted-foreground self-center w-16">Metric:</span>
                    {quickOptions.metric
                      .filter((opt) => {
                        if (!checklist.outputType.confirmed) return true; // Show all if task not selected
                        const isClassification = checklist.outputType.value?.toLowerCase().includes('classification');
                        return isClassification ? opt.type === 'classification' : opt.type === 'regression';
                      })
                      .map((opt) => (
                        <Button
                          key={opt.label}
                          variant="outline"
                          size="sm"
                          onClick={() => handleQuickOption(opt.message)}
                          disabled={isSending}
                          className="h-7 text-xs"
                        >
                          {opt.label}
                        </Button>
                      ))}
                  </div>
                )}

                {/* Model Family (optional) */}
                {!checklist.modelFamilyCategory.confirmed && (
                  <div className="flex flex-wrap gap-2">
                    <span className="text-xs text-muted-foreground self-center w-16">Models:</span>
                    {quickOptions.modelFamily.map((opt) => (
                      <Button
                        key={opt.label}
                        variant="outline"
                        size="sm"
                        onClick={() => handleQuickOption(opt.message)}
                        disabled={isSending}
                        className="h-7 text-xs"
                      >
                        {opt.label}
                      </Button>
                    ))}
                    <span className="text-xs text-muted-foreground self-center">(optional)</span>
                  </div>
                )}
              </div>

              {/* Text Input */}
              <div className="flex gap-2">
                <Input
                  value={inputValue}
                  onChange={(e) => setInputValue(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Or type your configuration..."
                  disabled={isSending}
                  className="flex-1"
                />
                <Button onClick={handleSend} disabled={!inputValue.trim() || isSending}>
                  {isSending ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Send className="h-4 w-4" />
                  )}
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Data Panel - 30% on desktop */}
      <div className="border-t border-border bg-card lg:w-[30%] lg:border-l lg:border-t-0">
        <ScrollArea className="h-full">
          <div className="space-y-4 p-4">
            {/* Training Data Upload */}
            <div>
              <h4 className="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
                <Database className="h-4 w-4" />
                Training Data
                {dataset && <Check className="h-4 w-4 text-primary" />}
              </h4>
              {!dataset ? (
                <div
                  onDragEnter={handleDragTrain}
                  onDragLeave={handleDragTrain}
                  onDragOver={handleDragTrain}
                  onDrop={handleDropTrain}
                  className={cn(
                    'flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-6 transition-colors',
                    dragActiveTrain ? 'border-primary bg-primary/5' : 'border-border',
                    isUploadingTrain && 'pointer-events-none opacity-50'
                  )}
                >
                  <input
                    ref={trainFileInputRef}
                    type="file"
                    accept=".csv,.parquet"
                    onChange={(e) => e.target.files?.[0] && handleTrainFileUpload(e.target.files[0])}
                    className="hidden"
                  />
                  {isUploadingTrain ? (
                    <Loader2 className="h-8 w-8 animate-spin text-primary" />
                  ) : (
                    <Upload className="h-8 w-8 text-muted-foreground" />
                  )}
                  <p className="mt-2 text-center text-xs text-muted-foreground">
                    {isUploadingTrain ? 'Uploading...' : 'Drop training CSV here'}
                  </p>
                  <Button
                    variant="outline"
                    size="sm"
                    className="mt-3"
                    onClick={() => trainFileInputRef.current?.click()}
                    disabled={isUploadingTrain}
                  >
                    <FileSpreadsheet className="mr-2 h-3 w-3" />
                    Select File
                  </Button>
                </div>
              ) : (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <FileSpreadsheet className="h-4 w-4" />
                      {dataset.filename}
                    </CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="flex gap-4 text-xs text-muted-foreground">
                      <span>{dataset.rowCount} rows</span>
                      <span>{dataset.columnCount} cols</span>
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>

            {/* Prediction Data Upload */}
            <div>
              <h4 className="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
                <BarChart3 className="h-4 w-4" />
                Prediction Data
                {predictionDataset && <Check className="h-4 w-4 text-primary" />}
              </h4>
              {!predictionDataset ? (
                <div
                  onDragEnter={handleDragPrediction}
                  onDragLeave={handleDragPrediction}
                  onDragOver={handleDragPrediction}
                  onDrop={handleDropPrediction}
                  className={cn(
                    'flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-6 transition-colors',
                    dragActivePrediction ? 'border-primary bg-primary/5' : 'border-border',
                    isUploadingPrediction && 'pointer-events-none opacity-50'
                  )}
                >
                  <input
                    ref={predictionFileInputRef}
                    type="file"
                    accept=".csv,.parquet"
                    onChange={(e) => e.target.files?.[0] && handlePredictionFileUpload(e.target.files[0])}
                    className="hidden"
                  />
                  {isUploadingPrediction ? (
                    <Loader2 className="h-8 w-8 animate-spin text-primary" />
                  ) : (
                    <Upload className="h-8 w-8 text-muted-foreground" />
                  )}
                  <p className="mt-2 text-center text-xs text-muted-foreground">
                    {isUploadingPrediction ? 'Uploading...' : 'Drop prediction CSV here'}
                  </p>
                  <Button
                    variant="outline"
                    size="sm"
                    className="mt-3"
                    onClick={() => predictionFileInputRef.current?.click()}
                    disabled={isUploadingPrediction}
                  >
                    <FileSpreadsheet className="mr-2 h-3 w-3" />
                    Select File
                  </Button>
                </div>
              ) : (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <FileSpreadsheet className="h-4 w-4" />
                      {predictionDataset.filename}
                    </CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="flex gap-4 text-xs text-muted-foreground">
                      <span>{predictionDataset.rowCount} rows</span>
                      <span>{predictionDataset.columnCount} cols</span>
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>

            <Separator />

            {/* Dataset Details (if training data uploaded) */}
            {dataset && (
              <>
                {/* Columns */}
                <div>
                  <h4 className="mb-2 text-sm font-medium text-foreground">Training Columns</h4>
                  <div className="space-y-1">
                    {dataset.columns.slice(0, 8).map((col) => (
                      <div
                        key={col.name}
                        className="flex items-center justify-between rounded-md bg-accent/50 px-3 py-1.5 text-xs"
                      >
                        <span className="truncate font-mono text-foreground">{col.name}</span>
                        <Badge variant="secondary" className="ml-2 shrink-0 text-xs">
                          {col.type}
                        </Badge>
                      </div>
                    ))}
                    {dataset.columns.length > 8 && (
                      <p className="px-3 text-xs text-muted-foreground">
                        +{dataset.columns.length - 8} more columns
                      </p>
                    )}
                  </div>
                </div>

                {/* Missing Values */}
                {dataset.missingSummary.length > 0 && (
                  <div>
                    <h4 className="mb-2 text-sm font-medium text-foreground">Missing Values</h4>
                    <div className="space-y-1">
                      {dataset.missingSummary.map((item) => (
                        <div
                          key={item.column}
                          className="flex items-center justify-between rounded-md bg-destructive/10 px-3 py-1.5 text-xs"
                        >
                          <span className="font-mono text-foreground">{item.column}</span>
                          <span className="text-muted-foreground">
                            {item.count} ({item.percentage.toFixed(1)}%)
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
};

export default Stage1Intake;
