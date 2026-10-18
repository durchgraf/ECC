"""Regression fixtures from the physical Soulear rotation measurement."""
import io
import json
import math
import unittest
from ms5_viewer import MS5Client, Handler
import ms5_viewer


class OrientationTests(unittest.TestCase):
    def test_page_exposes_upright_reference_calibration(self):
        page_fragments = [value for value in ms5_viewer.Handler._page.__code__.co_consts
                          if isinstance(value, str)]
        self.assertTrue(any('Calibrate this as down' in value for value in page_fragments))

    def test_page_offers_photo_and_recording_controls_without_direction_toggle(self):
        handler = Handler.__new__(Handler)
        handler.client = MS5Client()
        handler.client.width = 480
        handler.client.height = 480
        handler.wfile = io.BytesIO()
        handler.send_response = lambda code, message=None: self.assertEqual(code, 200)
        handler.send_header = lambda keyword, value: None
        handler.end_headers = lambda: None
        handler._page()
        page = handler.wfile.getvalue().decode('utf-8')
        self.assertIn("id='photo'", page)
        self.assertIn("id='record'", page)
        self.assertNotIn("id='direction'", page)
        self.assertIn('<title>ECC — Live Camera</title>', page)
        self.assertIn('>ECC <span', page)
        self.assertIn('width:100%', page)
        self.assertIn('Mirror: off', page)
        self.assertIn('Take photo', page)
        self.assertIn('Start recording', page)
        for german in ('Spiegeln', 'Ausrichten', 'aufnehmen', 'Warte auf Lagedaten'):
            self.assertNotIn(german, page)

    def test_decode_captured_gravity_vectors(self):
        for header, expected in [
            ('01a7990014010000a22162fc8002e001', (0, 128, -26)),
            ('01a6c10013015e7e292062fc8002e001', (-94, -95, -2)),
            ('01a7e9001201835a080062fc8002e001', (-131, -22, 0)),
        ]:
            with self.subTest(header=header):
                self.assertTrue(hasattr(ms5_viewer, 'decode_orientation'), 'orientation decoder missing')
                result = ms5_viewer.decode_orientation(bytes.fromhex(header))
                self.assertEqual(tuple(result['axes']), expected)
                self.assertAlmostEqual(result['angle'], math.degrees(math.atan2(expected[0], expected[1])))

    def test_no_angle_without_gravity_projection(self):
        self.assertTrue(hasattr(ms5_viewer, 'decode_orientation'), 'orientation decoder missing')
        result = ms5_viewer.decode_orientation(bytes(16))
        self.assertIsNone(result['angle'])
        self.assertIsNone(ms5_viewer.decode_orientation(bytes(8)))

    def test_orientation_endpoint_has_no_data_before_first_frame(self):
        self.assertTrue(hasattr(Handler, '_orientation'), 'orientation HTTP endpoint missing')
        handler = Handler.__new__(Handler)
        handler.client = MS5Client()
        handler.wfile = io.BytesIO()
        handler.send_response = lambda status: self.assertEqual(status, 200)
        headers = {}
        handler.send_header = lambda k, v: headers.update({k: v})
        handler.end_headers = lambda: None
        handler._orientation()
        data = json.loads(handler.wfile.getvalue())
        self.assertFalse(data['available'])
        self.assertIsNone(data['angle'])
        self.assertEqual(headers['Cache-Control'], 'no-store')


if __name__ == '__main__':
    unittest.main()
