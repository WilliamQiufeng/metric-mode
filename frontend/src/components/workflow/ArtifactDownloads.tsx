import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Download, FileCode, FileText, Table } from 'lucide-react';
import type { ArtifactMap } from '@/types/api';
import { getArtifactUrl } from '@/lib/apiService';

interface ArtifactDownloadsProps {
  artifacts: ArtifactMap;
  stage: 'stage2' | 'stage3';
}

const ArtifactDownloads = ({ artifacts, stage }: ArtifactDownloadsProps) => {
  const stage2Artifacts = [
    { key: 'modelPy', label: 'model.py', icon: FileCode, path: artifacts.modelPy },
    { key: 'testPy', label: 'test.py', icon: FileCode, path: artifacts.testPy },
  ];

  const stage3Artifacts = [
    { key: 'predictionsCsv', label: 'predictions.csv', icon: Table, path: artifacts.predictionsCsv },
    { key: 'explanationMd', label: 'explanation.md', icon: FileText, path: artifacts.explanationMd },
    { key: 'report1Pdf', label: 'Pipeline Report (PDF)', icon: FileText, path: artifacts.report1Pdf },
    { key: 'report2Pdf', label: 'Scientific Summary (PDF)', icon: FileText, path: artifacts.report2Pdf },
  ];

  const displayArtifacts = stage === 'stage2' ? stage2Artifacts : stage3Artifacts;
  const availableArtifacts = displayArtifacts.filter(a => a.path);

  if (availableArtifacts.length === 0) {
    return null;
  }

  const handleDownload = (path: string, filename: string) => {
    // In mock mode, just show the URL
    // In real mode, this would trigger a download
    const url = getArtifactUrl(path);
    console.log(`Downloading: ${url}`);
    
    // For demo, create a fake download
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    // link.click(); // Uncomment when backend is ready
    alert(`Would download: ${filename}\nURL: ${url}`);
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-base">
          <Download className="h-4 w-4" />
          {stage === 'stage2' ? 'Generated Code' : 'Results & Reports'}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="flex flex-wrap gap-2">
          {availableArtifacts.map(({ key, label, icon: Icon, path }) => (
            <Button
              key={key}
              variant="outline"
              size="sm"
              onClick={() => handleDownload(path!, label)}
            >
              <Icon className="mr-2 h-4 w-4" />
              {label}
            </Button>
          ))}
        </div>
      </CardContent>
    </Card>
  );
};

export default ArtifactDownloads;
