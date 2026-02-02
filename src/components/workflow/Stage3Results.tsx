import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { 
  CheckCircle2, 
  Circle, 
  Loader2, 
  Download, 
  FileText, 
  FileSpreadsheet,
  TrendingUp
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { 
  LineChart, 
  Line, 
  XAxis, 
  YAxis, 
  CartesianGrid, 
  Tooltip, 
  ResponsiveContainer,
  Legend,
  AreaChart,
  Area
} from 'recharts';
import { ScrollArea } from '@/components/ui/scroll-area';
import type { Stage3Artifacts } from '@/types/config';
import { 
  getArtifactDownloadUrl, 
  readFileContent, 
  readJsonFile,
  readCsvFile 
} from '@/lib/fileService';

interface Stage3ResultsProps {
  bestCandidateDir: string;
  artifacts: Stage3Artifacts;
  done: boolean;
}

interface WorkflowStep {
  id: string;
  label: string;
  icon: React.ReactNode;
  isComplete: boolean;
  isRunning: boolean;
}

const Stage3Results = ({ bestCandidateDir, artifacts, done }: Stage3ResultsProps) => {
  const [hyperparamData, setHyperparamData] = useState<{ trial: number; score: number; bestScore: number }[]>([]);
  const [predictions, setPredictions] = useState<{ id: number; predicted: number }[]>([]);
  const [explanationContent, setExplanationContent] = useState<string | null>(null);
  const [reportContent, setReportContent] = useState<string | null>(null);
  const [tuneResult, setTuneResult] = useState<Record<string, unknown> | null>(null);

  // Load content when artifacts become available
  useEffect(() => {
    const loadContent = async () => {
      // Load tune results for hyperparameter chart
      if (artifacts.tuneResultJson && hyperparamData.length === 0) {
        const tuneData = await readJsonFile<{ trials?: { score: number }[] }>(`${bestCandidateDir}/tune_result.json`);
        if (tuneData?.trials) {
          let best = 0;
          const data = tuneData.trials.map((trial, i) => {
            best = Math.max(best, trial.score);
            return {
              trial: i + 1,
              score: Math.round(trial.score * 1000) / 1000,
              bestScore: Math.round(best * 1000) / 1000,
            };
          });
          setHyperparamData(data);
        }
        setTuneResult(tuneData);
      }

      // Load predictions CSV
      if (artifacts.predictionsCsv && predictions.length === 0) {
        const csvData = await readCsvFile(`${bestCandidateDir}/predictions.csv`);
        if (csvData) {
          const predData = csvData.slice(0, 50).map((row, i) => ({
            id: i + 1,
            predicted: typeof row.predicted === 'number' ? row.predicted : 
                       typeof row.prediction === 'number' ? row.prediction :
                       parseFloat(String(Object.values(row)[0])) || 0,
          }));
          setPredictions(predData);
        }
      }

      // Load explanation markdown
      if (artifacts.explanationMd && !explanationContent) {
        const content = await readFileContent(`${bestCandidateDir}/explanation.md`);
        setExplanationContent(content);
      }

      // Load report markdown
      if (artifacts.reportMd && !reportContent) {
        const content = await readFileContent(`${bestCandidateDir}/report.md`);
        setReportContent(content);
      }
    };

    loadContent();
  }, [artifacts, bestCandidateDir, hyperparamData.length, predictions.length, explanationContent, reportContent]);

  // Workflow steps based on artifacts
  const workflowSteps: WorkflowStep[] = [
    { 
      id: 'tune', 
      label: 'Hyperparameter Tuning', 
      icon: <TrendingUp className="h-4 w-4" />,
      isComplete: artifacts.tuneResultJson,
      isRunning: !artifacts.tuneResultJson && !done,
    },
    { 
      id: 'train', 
      label: 'Final Training', 
      icon: <FileSpreadsheet className="h-4 w-4" />,
      isComplete: artifacts.trainedModelJson,
      isRunning: artifacts.tuneResultJson && !artifacts.trainedModelJson && !done,
    },
    { 
      id: 'predictions', 
      label: 'Generate Predictions', 
      icon: <FileSpreadsheet className="h-4 w-4" />,
      isComplete: artifacts.predictionsCsv,
      isRunning: artifacts.trainedModelJson && !artifacts.predictionsCsv && !done,
    },
    { 
      id: 'explanation', 
      label: 'Model Explanation', 
      icon: <FileText className="h-4 w-4" />,
      isComplete: artifacts.explanationMd,
      isRunning: artifacts.predictionsCsv && !artifacts.explanationMd && !done,
    },
    { 
      id: 'report', 
      label: 'Generate Report', 
      icon: <FileText className="h-4 w-4" />,
      isComplete: artifacts.reportPdf,
      isRunning: artifacts.explanationMd && !artifacts.reportPdf && !done,
    },
  ];

  const getStepIcon = (step: WorkflowStep) => {
    if (step.isComplete) {
      return <CheckCircle2 className="h-5 w-5 text-green-500" />;
    }
    if (step.isRunning) {
      return <Loader2 className="h-5 w-5 text-primary animate-spin" />;
    }
    return <Circle className="h-5 w-5 text-muted-foreground" />;
  };

  const handleDownload = (filename: string) => {
    const url = getArtifactDownloadUrl(`${bestCandidateDir}/${filename}`);
    window.open(url, '_blank');
  };

  return (
    <div className="h-[calc(100vh-140px)] overflow-auto p-6">
      <div className="mx-auto max-w-6xl space-y-6">
        {/* Header */}
        <div className="text-center">
          <h2 className="text-2xl font-bold text-foreground">Results & Reports</h2>
          <p className="mt-2 text-muted-foreground">
            {done ? 'All outputs generated' : 'Generating final predictions and documentation'}
          </p>
          <p className="mt-1 text-xs text-muted-foreground font-mono truncate">
            {bestCandidateDir}
          </p>
        </div>

        {/* Workflow Pipeline */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">Generation Pipeline</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="relative">
              {/* Connection Line */}
              <div className="absolute top-6 left-8 right-8 h-0.5 bg-muted z-0" />
              
              {/* Steps */}
              <div className="relative z-10 flex justify-between">
                {workflowSteps.map((step) => (
                  <div key={step.id} className="flex flex-col items-center w-24">
                    {/* Node */}
                    <div
                      className={cn(
                        'w-12 h-12 rounded-full border-2 flex items-center justify-center transition-all duration-300',
                        step.isComplete && 'border-green-500 bg-green-500/10',
                        step.isRunning && 'border-primary bg-primary/10',
                        !step.isComplete && !step.isRunning && 'border-muted bg-card'
                      )}
                    >
                      {getStepIcon(step)}
                    </div>
                    
                    {/* Label */}
                    <p className={cn(
                      'mt-2 text-xs text-center font-medium',
                      step.isComplete ? 'text-foreground' : 'text-muted-foreground'
                    )}>
                      {step.label}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Hyperparameter Tuning Chart */}
        {hyperparamData.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <TrendingUp className="h-4 w-4" />
                Hyperparameter Tuning Progress
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={hyperparamData}>
                    <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                    <XAxis 
                      dataKey="trial" 
                      label={{ value: 'Trial', position: 'insideBottom', offset: -5 }}
                      className="text-xs"
                    />
                    <YAxis 
                      domain={['auto', 'auto']}
                      label={{ value: 'Score', angle: -90, position: 'insideLeft' }}
                      className="text-xs"
                    />
                    <Tooltip 
                      contentStyle={{ 
                        backgroundColor: 'hsl(var(--card))',
                        border: '1px solid hsl(var(--border))',
                        borderRadius: '8px'
                      }}
                    />
                    <Legend />
                    <Line 
                      type="monotone" 
                      dataKey="score" 
                      stroke="hsl(var(--muted-foreground))" 
                      strokeWidth={2}
                      dot={{ fill: 'hsl(var(--muted-foreground))', r: 3 }}
                      name="Trial Score"
                    />
                    <Line 
                      type="monotone" 
                      dataKey="bestScore" 
                      stroke="hsl(var(--primary))" 
                      strokeWidth={2}
                      strokeDasharray="5 5"
                      dot={false}
                      name="Best So Far"
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Predictions Chart */}
        {predictions.length > 0 && (
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="text-base flex items-center gap-2">
                <FileSpreadsheet className="h-4 w-4" />
                Model Predictions
              </CardTitle>
              <Button
                variant="ghost"
                size="sm"
                className="h-8 gap-1"
                onClick={() => handleDownload('predictions.csv')}
              >
                <Download className="h-3 w-3" />
                CSV
              </Button>
            </CardHeader>
            <CardContent>
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={predictions}>
                    <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                    <XAxis 
                      dataKey="id" 
                      label={{ value: 'Sample ID', position: 'insideBottom', offset: -5 }}
                      className="text-xs"
                    />
                    <YAxis 
                      label={{ value: 'Predicted Value', angle: -90, position: 'insideLeft' }}
                      className="text-xs"
                    />
                    <Tooltip 
                      contentStyle={{ 
                        backgroundColor: 'hsl(var(--card))',
                        border: '1px solid hsl(var(--border))',
                        borderRadius: '8px'
                      }}
                    />
                    <Area 
                      type="monotone" 
                      dataKey="predicted" 
                      stroke="hsl(var(--primary))" 
                      fill="hsl(var(--primary))"
                      fillOpacity={0.2}
                      strokeWidth={2}
                      name="Predicted"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Reports Section */}
        {(artifacts.explanationMd || artifacts.reportPdf) && (
          <div className="grid gap-6 md:grid-cols-2">
            {/* Explanation */}
            {artifacts.explanationMd && (
              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <FileText className="h-4 w-4" />
                    Model Explanation
                  </CardTitle>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 gap-1"
                    onClick={() => handleDownload('explanation.md')}
                  >
                    <Download className="h-3 w-3" />
                    MD
                  </Button>
                </CardHeader>
                <CardContent>
                  <ScrollArea className="h-72">
                    <div className="prose prose-sm dark:prose-invert max-w-none text-sm text-muted-foreground whitespace-pre-wrap">
                      {explanationContent || 'Loading...'}
                    </div>
                  </ScrollArea>
                </CardContent>
              </Card>
            )}

            {/* Report */}
            {artifacts.reportPdf && (
              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <FileText className="h-4 w-4" />
                    Pipeline Report
                  </CardTitle>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 gap-1"
                    onClick={() => handleDownload('report.pdf')}
                  >
                    <Download className="h-3 w-3" />
                    PDF
                  </Button>
                </CardHeader>
                <CardContent>
                  <ScrollArea className="h-72">
                    <div className="prose prose-sm dark:prose-invert max-w-none text-sm text-muted-foreground whitespace-pre-wrap">
                      {reportContent || (artifacts.reportMd ? 'Loading...' : 'Report PDF available for download')}
                    </div>
                  </ScrollArea>
                </CardContent>
              </Card>
            )}
          </div>
        )}

        {/* Completion Banner */}
        {done && (
          <Card className="border-2 border-green-500 bg-green-500/5">
            <CardContent className="py-6 text-center">
              <CheckCircle2 className="mx-auto h-10 w-10 text-green-500" />
              <h3 className="mt-3 text-lg font-semibold text-foreground">
                Pipeline Complete!
              </h3>
              <p className="mt-1 text-muted-foreground">
                All artifacts have been generated successfully.
              </p>
              <div className="mt-4 flex justify-center gap-2">
                <Button onClick={() => handleDownload('predictions.csv')} variant="outline" size="sm">
                  <Download className="h-4 w-4 mr-1" />
                  Predictions
                </Button>
                <Button onClick={() => handleDownload('explanation.md')} variant="outline" size="sm">
                  <Download className="h-4 w-4 mr-1" />
                  Explanation
                </Button>
                <Button onClick={() => handleDownload('report.pdf')} size="sm">
                  <Download className="h-4 w-4 mr-1" />
                  Report
                </Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
};

export default Stage3Results;
