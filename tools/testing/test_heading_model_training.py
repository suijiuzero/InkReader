import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import sys
import add_heading_sample
from train_heading_model import train, metrics, read


class TrainingTests(unittest.TestCase):
    def rows(self):
        return [dict(group=split, split=split, line=i+1, y=y, x=[float(y)])
                for split in ['train','validation','holdout'] for i,y in enumerate([0,0,1,1])]

    def test_holdout_labels_and_features_cannot_change_fitted_weights_or_threshold(self):
        rows = self.rows()
        first = train(['feature'], rows)
        for row in rows:
            if row['split']=='holdout': row.update(y=1-row['y'],x=[900.0])
        self.assertEqual(first, train(['feature'], rows))

    def test_retraining_is_deterministic(self):
        self.assertEqual(train(['feature'], self.rows()), train(['feature'], self.rows()))

    def test_candidate_budget_misses_stay_in_recall_denominator(self):
        rows = [dict(line=0,y=3,x=[0.0]),dict(line=1,y=1,x=[1.0])]
        result = metrics(rows, [0.0,5.0],0.9)
        self.assertEqual((1,0,2),(result['TP'],result['FP'],result['FN']))

    def test_same_work_cannot_cross_development_and_holdout_under_another_dataset(self):
        with TemporaryDirectory() as directory:
            path=Path(directory)/'features.tsv'
            path.write_text('dataset\tcase\tpartition\tgroup\tline\tlabel\tf\n'
                            'real-source-v3\tC01\tholdout\t24264\t1\t1\t0.5\n'
                            'heading-training-extra\tnew\tdevelopment\treal-source-v3/24264\t1\t1\t0.5\n',encoding='utf-8')
            with self.assertRaises(AssertionError): read(path)

    def test_new_reviewed_sample_refuses_to_overwrite_an_existing_case(self):
        with TemporaryDirectory() as directory:
            root=Path(directory); source=root/'book.txt'
            source.write_text('First title\n\nFull body.\n\nSecond title\n\nMore body.',encoding='utf-8')
            args=['add_heading_sample.py',str(source),'--id','one','--group','one-work',
                  '--partition','development','--lines','1,5','--reviewed']
            with patch.object(add_heading_sample,'ROOT',root/'samples'),patch.object(sys,'argv',args):
                add_heading_sample.main()
                before=(root/'samples/one.txt').read_bytes()
                with patch('sys.stderr'),self.assertRaises(SystemExit): add_heading_sample.main()
                self.assertEqual(before,(root/'samples/one.txt').read_bytes())

    def test_nonfinite_export_is_rejected_before_training(self):
        with TemporaryDirectory() as directory:
            path=Path(directory)/'bad.tsv'
            path.write_text('dataset\tcase\tpartition\tgroup\tline\tlabel\tf\n'
                            'extra\tbad\tdevelopment\twork\t1\t1\tNaN\n',encoding='utf-8')
            with self.assertRaises(AssertionError): read(path)


if __name__ == '__main__': unittest.main()
