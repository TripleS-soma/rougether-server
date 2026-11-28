import copy
import importlib.util
from pathlib import Path
from unittest import TestCase, main, mock

path=Path(__file__).resolve().parents[1]/'source_cloud_cutover.py'
spec=importlib.util.spec_from_file_location('controls',path)
cloud=importlib.util.module_from_spec(spec)
spec.loader.exec_module(cloud)


class ControlsTest(TestCase):
    def setUp(self):
        self.original={'Version':'2012-10-17','Statement':[{'Sid':'Existing','Effect':'Deny'}]}
        self.sealed=copy.deepcopy(self.original)
        self.sealed['Statement'].append(cloud.FENCE)
        for key,value in [('ORIGINAL',self.original),('SEALED',self.sealed)]:
            patch=mock.patch.object(cloud,key,cloud.digest(value))
            patch.start();self.addCleanup(patch.stop)
        self.control=cloud.SourceControls.__new__(cloud.SourceControls)
        self.control.lm=mock.Mock()
        self.control.lm.get_function_configuration.return_value={'Timeout':600}
        self.control.limits=mock.Mock(return_value=dict(cloud.WORKERS))
        self.control.mapping=mock.Mock(return_value='Enabled')
        self.control.upload_count=mock.Mock(return_value=0)
        self.control.read_policy=mock.Mock(return_value=self.original)
        self.control.change_mapping=mock.Mock(side_effect=lambda enabled:setattr(self.control.mapping,'return_value','Enabled' if enabled else 'Disabled'))
        self.control.queue_counts=mock.Mock(return_value={'jobs':{'inflight':0},'dlq':{'visible':0}})
        self.control.set_limits=mock.Mock(side_effect=lambda seal:setattr(self.control.limits,'return_value',{fn:0 if seal else n for fn,n in cloud.WORKERS.items()}))
        self.control.set_policy=mock.Mock(side_effect=lambda seal:setattr(self.control.read_policy,'return_value',self.sealed if seal else self.original))

    def test_fails_on_unreviewed_policy_even_on_retry(self):
        with self.assertRaises(RuntimeError):cloud.policy_for({'Statement':[]},True)
        self.assertEqual(cloud.policy_for(self.sealed,False),self.original)
        self.assertEqual(cloud.policy_for(self.original,True),self.sealed)

    def test_intake_and_settlement_and_empty_queues_needed(self):
        for route,settled in [('open',True),('closed',False)]:
            with self.assertRaises(RuntimeError):self.control.apply('stop-workers',ingress=lambda:route,settled=settled)
        self.control.queue_counts.return_value={'jobs':{'inflight':1}}
        with self.assertRaises(RuntimeError):self.control.apply('stop-workers',ingress=lambda:'closed',settled=True)
        self.control.change_mapping.assert_not_called()

    @mock.patch.object(cloud.time,'sleep')
    def test_full_drain_then_object_seal(self,sleep):
        self.control.apply('stop-workers',ingress=lambda:'closed',settled=True)
        sleep.assert_called_once_with(605)
        with self.assertRaises(RuntimeError):self.control.apply('seal-assets',ingress=lambda:'closed')
        self.control.upload_count.return_value=1
        with self.assertRaises(RuntimeError):self.control.apply('seal-assets',ingress=lambda:'held')
        self.control.set_policy.assert_not_called()
        self.control.upload_count.return_value=0
        proof=self.control.apply('seal-assets',ingress=lambda:'held')
        self.assertEqual(proof['policyDigest'],cloud.SEALED)

    def test_rollback_with_stranded_messages_and_target_sealed(self):
        self.control.queue_counts.return_value={'jobs':{'delayed':1}}
        with self.assertRaises(RuntimeError):self.control.apply('restore-cloud',ingress=lambda:'held')
        self.control.apply('restore-cloud',ingress=lambda:'held',target_sealed=True)
        self.control.change_mapping.assert_called_once_with(True)

if __name__=='__main__':main()
