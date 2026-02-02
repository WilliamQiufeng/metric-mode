import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { ArrowRight, Database, Brain, FileText, Sparkles } from 'lucide-react';
import { useSession } from '@/hooks/useSession';

const Landing = () => {
  const navigate = useNavigate();
  const { createSession, getStoredSessionId } = useSession();
  const [existingSessionId, setExistingSessionId] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    const stored = getStoredSessionId();
    setExistingSessionId(stored);
  }, [getStoredSessionId]);

  const handleNewSession = async () => {
    setIsCreating(true);
    const sessionId = await createSession();
    navigate(`/workflow/${sessionId}`);
  };

  const handleResumeSession = () => {
    if (existingSessionId) {
      navigate(`/workflow/${existingSessionId}`);
    }
  };

  const features = [
    {
      icon: Database,
      title: 'Data Profiling',
      description: 'Upload your CSV and get instant insights about column types, missing values, and data quality.',
    },
    {
      icon: Brain,
      title: 'Intelligent Pipeline',
      description: 'Our AI agent asks the right questions to configure an optimal ML workflow for your research.',
    },
    {
      icon: FileText,
      title: 'Reproducible Reports',
      description: 'Generate publication-ready PDF reports with methods, results, and code for your paper supplements.',
    },
  ];

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <header className="border-b border-border bg-card">
        <div className="container mx-auto px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary">
              <Sparkles className="h-5 w-5 text-primary-foreground" />
            </div>
            <span className="text-xl font-semibold text-foreground">Metric Mode</span>
          </div>
        </div>
      </header>

      {/* Hero Section */}
      <main className="container mx-auto px-6 py-16">
        <div className="mx-auto max-w-3xl text-center">
          <h1 className="mb-6 font-serif text-4xl font-bold tracking-tight text-foreground md:text-5xl">
            Turn experimental data into
            <span className="text-primary"> trustworthy ML workflows</span>
          </h1>
          <p className="mb-10 text-lg text-muted-foreground">
            Metric Mode is an AI research assistant that helps scientists build machine learning pipelines
            without programming expertise. Upload your data, describe your goal, and get reproducible
            training scripts and publication-ready reports.
          </p>

          <div className="flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
            <Button
              size="lg"
              onClick={handleNewSession}
              disabled={isCreating}
              className="min-w-[180px]"
            >
              {isCreating ? 'Creating...' : 'New Session'}
              <ArrowRight className="ml-2 h-4 w-4" />
            </Button>
            
            {existingSessionId && (
              <Button
                size="lg"
                variant="outline"
                onClick={handleResumeSession}
                className="min-w-[180px]"
              >
                Resume Session
              </Button>
            )}
          </div>
        </div>

        {/* Features */}
        <div className="mx-auto mt-20 grid max-w-5xl gap-6 md:grid-cols-3">
          {features.map((feature) => (
            <Card key={feature.title} className="border-border bg-card">
              <CardHeader>
                <div className="mb-2 flex h-12 w-12 items-center justify-center rounded-lg bg-accent">
                  <feature.icon className="h-6 w-6 text-accent-foreground" />
                </div>
                <CardTitle className="text-lg text-card-foreground">{feature.title}</CardTitle>
              </CardHeader>
              <CardContent>
                <CardDescription className="text-muted-foreground">
                  {feature.description}
                </CardDescription>
              </CardContent>
            </Card>
          ))}
        </div>

        {/* Workflow Preview */}
        <div className="mx-auto mt-20 max-w-4xl">
          <h2 className="mb-8 text-center font-serif text-2xl font-semibold text-foreground">
            Three-Stage Workflow
          </h2>
          <div className="grid gap-4 md:grid-cols-3">
            {[
              { stage: 1, title: 'Interactive Intake', desc: 'Upload data, describe goals, configure pipeline' },
              { stage: 2, title: 'Pipeline Generation', desc: 'AI generates and tests multiple model candidates' },
              { stage: 3, title: 'Results & Reports', desc: 'Review results, download predictions and PDF reports' },
            ].map((item) => (
              <div
                key={item.stage}
                className="flex items-start gap-4 rounded-lg border border-border bg-card p-4"
              >
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-medium text-primary-foreground">
                  {item.stage}
                </div>
                <div>
                  <h3 className="font-medium text-card-foreground">{item.title}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">{item.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-border bg-card py-8">
        <div className="container mx-auto px-6 text-center text-sm text-muted-foreground">
          <p>Metric Mode — AI-powered ML workflows for scientists</p>
        </div>
      </footer>
    </div>
  );
};

export default Landing;
